package iot.platform.simulator.payload;

import iot.platform.control.CommandProtocol;
import iot.platform.control.proto.SamplingAcknowledgment;
import iot.platform.control.proto.SamplingCommand;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.TelemetryReceipt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Each instance is owned by the simulation scheduler. */
final class SimulatorOutbox implements AutoCloseable {
  private final Connection db;
  private final long limitPerTenant;
  private final boolean faultTrace;

  private SimulatorOutbox(Connection db, long limitPerTenant, boolean faultTrace) {
    this.db = db;
    this.limitPerTenant = limitPerTenant;
    this.faultTrace = faultTrace;
  }

  static SimulatorOutbox open(Path path, long limitPerTenant) throws IOException, SQLException {
    return open(path, limitPerTenant, false);
  }

  static SimulatorOutbox open(Path path, long limitPerTenant, boolean faultTrace)
      throws IOException, SQLException {
    if (limitPerTenant <= 0) {
      throw new IllegalArgumentException("Outbox capacity must be positive");
    }
    var file = path.toAbsolutePath();
    Files.createDirectories(java.util.Objects.requireNonNull(file.getParent()));
    var db = DriverManager.getConnection("jdbc:sqlite:" + file);
    try (var sql = db.createStatement()) {
      sql.execute("PRAGMA journal_mode=WAL");
      sql.execute("PRAGMA synchronous=FULL");
      sql.execute("PRAGMA busy_timeout=5000");
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS checkpoints (
              tenant TEXT NOT NULL, sensor TEXT NOT NULL, sequence INTEGER NOT NULL,
              observed_ms INTEGER NOT NULL, PRIMARY KEY (tenant, sensor)
          )
          """);
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS pending (
              tenant TEXT NOT NULL, device TEXT NOT NULL, sensor TEXT NOT NULL,
              event TEXT NOT NULL, payload BLOB NOT NULL, fingerprint BLOB NOT NULL,
              next_ms INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (tenant, sensor, event)
          )
          """);
      sql.execute("CREATE INDEX IF NOT EXISTS due ON pending (tenant, device, next_ms, event)");
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS stats (
              tenant TEXT PRIMARY KEY, generated INTEGER NOT NULL DEFAULT 0,
              generated_bytes INTEGER NOT NULL DEFAULT 0, dropped INTEGER NOT NULL DEFAULT 0,
              pending_events INTEGER NOT NULL DEFAULT 0, pending_bytes INTEGER NOT NULL DEFAULT 0,
              accepted INTEGER NOT NULL DEFAULT 0, rejected INTEGER NOT NULL DEFAULT 0
          )
          """);
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS sampling_state (
              tenant TEXT NOT NULL, sensor TEXT NOT NULL, command TEXT NOT NULL,
              version INTEGER NOT NULL, enabled INTEGER NOT NULL, applied_ms INTEGER NOT NULL,
              PRIMARY KEY (tenant, sensor)
          )
          """);
      if (faultTrace) {
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS fault_events (
              tenant TEXT NOT NULL, sensor TEXT NOT NULL, event TEXT NOT NULL,
              observed_ms INTEGER NOT NULL, outcome TEXT NOT NULL,
              PRIMARY KEY (tenant, sensor, event)
            )
            """);
      }
    } catch (SQLException failure) {
      db.close();
      throw failure;
    }
    return new SimulatorOutbox(db, limitPerTenant, faultTrace);
  }

  SamplingAcknowledgment sampling(SensorStream stream) throws SQLException {
    try (var query =
        db.prepareStatement(
            """
        SELECT command,version,enabled,applied_ms FROM sampling_state WHERE tenant=? AND sensor=?
        """)) {
      query.setString(1, stream.device().tenantId().toString());
      query.setString(2, stream.sensorId().toString());
      try (var row = query.executeQuery()) {
        return row.next()
            ? acknowledgment(
                stream, row.getString(1), row.getLong(2), row.getBoolean(3), row.getLong(4))
            : null;
      }
    }
  }

  SamplingAcknowledgment apply(SensorStream stream, SamplingCommand command, long now)
      throws SQLException {
    if (!CommandProtocol.valid(command)
        || !stream.sensorId().toString().equals(command.getSensorId())) {
      return null;
    }
    return transaction(
        () -> {
          var prior = sampling(stream);
          if (prior != null && command.getVersion() <= prior.getVersion()) {
            return prior.getVersion() == command.getVersion()
                    && prior.getCommandId().equals(command.getCommandId())
                    && prior.getEnabled() == command.getEnabled()
                ? prior
                : null;
          }
          if (now >= command.getExpiresAtMs()) {
            return null;
          }
          try (var write =
              db.prepareStatement(
                  """
          INSERT INTO sampling_state VALUES (?,?,?,?,?,?)
          ON CONFLICT (tenant,sensor) DO UPDATE SET command=excluded.command,
            version=excluded.version,enabled=excluded.enabled,applied_ms=excluded.applied_ms
          """)) {
            write.setString(1, stream.device().tenantId().toString());
            write.setString(2, stream.sensorId().toString());
            write.setString(3, command.getCommandId());
            write.setLong(4, command.getVersion());
            write.setBoolean(5, command.getEnabled());
            write.setLong(6, now);
            write.executeUpdate();
          }
          return acknowledgment(
              stream, command.getCommandId(), command.getVersion(), command.getEnabled(), now);
        });
  }

  private static SamplingAcknowledgment acknowledgment(
      SensorStream stream, String command, long version, boolean enabled, long now) {
    return SamplingAcknowledgment.newBuilder()
        .setSchemaVersion(CommandProtocol.VERSION)
        .setSensorId(stream.sensorId().toString())
        .setCommandId(command)
        .setVersion(version)
        .setEnabled(enabled)
        .setAppliedAtMs(now)
        .build();
  }

  void generate(Collection<Intent> events, long seed) throws SQLException {
    transaction(
        () -> {
          for (var intent : events) {
            var stream = intent.stream();
            var tenant = stream.device().tenantId().toString();
            var sensor = stream.sensorId().toString();
            long sequence = 1;
            try (var find =
                db.prepareStatement(
                    "SELECT sequence, observed_ms FROM checkpoints WHERE tenant=? AND sensor=?")) {
              find.setString(1, tenant);
              find.setString(2, sensor);
              try (var checkpoint = find.executeQuery()) {
                if (checkpoint.next()) {
                  if (checkpoint.getLong("observed_ms") >= intent.observedMs()) {
                    continue;
                  }
                  sequence = checkpoint.getLong("sequence") + 1;
                }
              }
            }

            var event = SensorReadings.sample(stream, seed, sequence, intent.observedMs());
            byte[] payload = event.toByteArray();
            boolean stored =
                stats(stream.device().tenantId()).pendingBytes() <= limitPerTenant - payload.length;
            if (stored) {
              try (var insert =
                  db.prepareStatement(
                      """
              INSERT INTO pending (tenant, device, sensor, event, payload, fingerprint, next_ms)
              VALUES (?, ?, ?, ?, ?, ?, ?)
              """)) {
                insert.setString(1, tenant);
                insert.setString(2, stream.device().deviceId().toString());
                insert.setString(3, sensor);
                insert.setString(4, event.getEventId());
                insert.setBytes(5, payload);
                insert.setBytes(6, fingerprint(payload));
                insert.setLong(7, intent.observedMs());
                insert.executeUpdate();
              }
            }

            if (faultTrace) {
              traceGeneration(tenant, sensor, event.getEventId(), intent.observedMs(), stored);
            }
            try (var update =
                db.prepareStatement(
                    """
            INSERT INTO checkpoints VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant, sensor) DO UPDATE SET sequence=excluded.sequence,
                observed_ms=excluded.observed_ms
            """)) {
              update.setString(1, tenant);
              update.setString(2, sensor);
              update.setLong(3, sequence);
              update.setLong(4, intent.observedMs());
              update.executeUpdate();
            }
            try (var update =
                db.prepareStatement(
                    """
            INSERT INTO stats (tenant, generated, generated_bytes, dropped, pending_events, pending_bytes)
            VALUES (?, 1, ?, ?, ?, ?)
            ON CONFLICT (tenant) DO UPDATE SET generated=generated+1,
              generated_bytes=generated_bytes+excluded.generated_bytes, dropped=dropped+excluded.dropped,
              pending_events=pending_events+excluded.pending_events, pending_bytes=pending_bytes+excluded.pending_bytes
            """)) {
              update.setString(1, tenant);
              update.setInt(2, payload.length);
              update.setInt(3, stored ? 0 : 1);
              update.setInt(4, stored ? 1 : 0);
              update.setInt(5, stored ? payload.length : 0);
              update.executeUpdate();
            }
          }
          return null;
        });
  }

  List<Pending> due(SensorStream.Device device, long nowMs, int limit) throws SQLException {
    return transaction(
        () -> {
          var selected = new ArrayList<Pending>();
          try (var query =
              db.prepareStatement(
                  """
          SELECT sensor, event, payload, attempts FROM pending
          WHERE tenant=? AND device=? AND next_ms<=? ORDER BY next_ms, event LIMIT ?
          """)) {
            query.setString(1, device.tenantId().toString());
            query.setString(2, device.deviceId().toString());
            query.setLong(3, nowMs);
            query.setInt(4, limit);
            try (var rows = query.executeQuery()) {
              while (rows.next()) {
                selected.add(
                    new Pending(
                        rows.getString(1), rows.getString(2), rows.getBytes(3), rows.getInt(4)));
              }
            }
          }
          try (var defer =
              db.prepareStatement(
                  """
          UPDATE pending SET next_ms=?, attempts=attempts+1 WHERE tenant=? AND sensor=? AND event=?
          """)) {
            for (var delivery : selected) {
              long delay = Math.min(60_000, 2000L << Math.min(delivery.attempts(), 5));
              defer.setLong(1, nowMs + delay + Math.floorMod(delivery.eventId().hashCode(), 500));
              defer.setString(2, device.tenantId().toString());
              defer.setString(3, delivery.sensorId());
              defer.setString(4, delivery.eventId());
              defer.addBatch();
            }
            defer.executeBatch();
          }
          return selected;
        });
  }

  boolean acknowledge(SensorStream stream, TelemetryReceipt receipt) throws SQLException {
    var outcome = receipt.getOutcome();
    if (receipt.getSchemaVersion() != TelemetryValidation.VERSION
        || receipt.getFingerprint().size() != 32
        || (outcome != TelemetryReceipt.Outcome.ACCEPTED
            && outcome != TelemetryReceipt.Outcome.DUPLICATE
            && outcome != TelemetryReceipt.Outcome.REJECTED)) {
      return false;
    }
    return transaction(
        () -> {
          int bytes;
          var tenant = stream.device().tenantId().toString();
          try (var delete =
              db.prepareStatement(
                  """
          DELETE FROM pending WHERE tenant=? AND device=? AND sensor=? AND event=? AND fingerprint=?
          RETURNING length(payload)
          """)) {
            delete.setString(1, tenant);
            delete.setString(2, stream.device().deviceId().toString());
            delete.setString(3, stream.sensorId().toString());
            delete.setString(4, receipt.getEventId());
            delete.setBytes(5, receipt.getFingerprint().toByteArray());
            try (var row = delete.executeQuery()) {
              if (!row.next()) {
                return false;
              }
              bytes = row.getInt(1);
            }
          }
          boolean rejected = outcome == TelemetryReceipt.Outcome.REJECTED;
          if (faultTrace) {
            traceReceipt(tenant, stream.sensorId().toString(), receipt.getEventId(), rejected);
          }
          try (var update =
              db.prepareStatement(
                  """
          UPDATE stats SET pending_events=pending_events-1, pending_bytes=pending_bytes-?,
            accepted=accepted+?, rejected=rejected+? WHERE tenant=?
          """)) {
            update.setInt(1, bytes);
            update.setInt(2, rejected ? 0 : 1);
            update.setInt(3, rejected ? 1 : 0);
            update.setString(4, tenant);
            update.executeUpdate();
          }
          return true;
        });
  }

  private void traceGeneration(
      String tenant, String sensor, String event, long observedMs, boolean queued)
      throws SQLException {
    try (var entry = db.prepareStatement("INSERT INTO fault_events VALUES (?, ?, ?, ?, ?)")) {
      entry.setString(1, tenant);
      entry.setString(2, sensor);
      entry.setString(3, event);
      entry.setLong(4, observedMs);
      entry.setString(5, queued ? "pending" : "dropped");
      entry.executeUpdate();
    }
  }

  private void traceReceipt(String tenant, String sensor, String event, boolean rejected)
      throws SQLException {
    try (var update =
        db.prepareStatement(
            "UPDATE fault_events SET outcome=? WHERE tenant=? AND sensor=? AND event=?")) {
      update.setString(1, rejected ? "rejected" : "accepted");
      update.setString(2, tenant);
      update.setString(3, sensor);
      update.setString(4, event);
      update.executeUpdate();
    }
  }

  Stats stats(UUID tenant) throws SQLException {
    try (var query =
        db.prepareStatement(
            """
        SELECT generated, generated_bytes, dropped, pending_events, pending_bytes, accepted, rejected
        FROM stats WHERE tenant=?
        """)) {
      query.setString(1, tenant.toString());
      try (var row = query.executeQuery()) {
        return row.next()
            ? new Stats(
                row.getLong(1),
                row.getLong(2),
                row.getLong(3),
                row.getLong(4),
                row.getLong(5),
                row.getLong(6),
                row.getLong(7))
            : new Stats(0, 0, 0, 0, 0, 0, 0);
      }
    }
  }

  @Override
  public void close() throws SQLException {
    db.close();
  }

  static byte[] fingerprint(byte[] payload) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(payload);
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("Required SHA-256 algorithm unavailable", missing);
    }
  }

  private <T> T transaction(SqlAction<T> action) throws SQLException {
    // Use explicit transaction control to avoid JDBC committing during autocommit reset.
    try (var sql = db.createStatement()) {
      sql.execute("BEGIN");
      try {
        T result = action.run();
        sql.execute("COMMIT");
        return result;
      } catch (SQLException | RuntimeException failure) {
        try {
          sql.execute("ROLLBACK");
        } catch (SQLException cleanup) {
          // SQLite can end the transaction itself when a write fails.
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }
  }

  private interface SqlAction<T> {
    T run() throws SQLException;
  }

  record Intent(SensorStream stream, long observedMs) {}

  record Pending(String sensorId, String eventId, byte[] payload, int attempts) {}

  record Stats(
      long generated,
      long generatedBytes,
      long dropped,
      long pendingEvents,
      long pendingBytes,
      long accepted,
      long rejected) {}
}
