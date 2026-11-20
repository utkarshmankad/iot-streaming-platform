package iot.platform.gateway.ingest;

import com.google.protobuf.ByteString;
import iot.platform.telemetry.proto.TelemetryReceipt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** The gateway ingestion scheduler owns the SQLite connection. */
final class GatewaySpool implements AutoCloseable {
  static final long LIVE_WINDOW_MS = 5_000;
  private final Connection db;
  private final long limit;

  private GatewaySpool(Connection db, long limit) {
    this.db = db;
    this.limit = limit;
  }

  static GatewaySpool open(Path path, long limit) throws IOException, SQLException {
    if (limit <= 0) {
      throw new IllegalArgumentException("Gateway spool capacity must be positive");
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
          CREATE TABLE IF NOT EXISTS spool (
            id INTEGER PRIMARY KEY, tenant TEXT NOT NULL, device TEXT NOT NULL,
            sensor TEXT NOT NULL, event TEXT NOT NULL, payload BLOB NOT NULL,
            fingerprint BLOB NOT NULL, received_ms INTEGER NOT NULL,
            retry_ms INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
            UNIQUE (tenant, sensor, event)
          )
          """);
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS command_acks (
            tenant TEXT NOT NULL, command TEXT NOT NULL, payload BLOB NOT NULL,
            PRIMARY KEY (tenant, command)
          )
          """);
      sql.execute("CREATE INDEX IF NOT EXISTS spool_received ON spool (tenant, received_ms)");
      sql.execute(
          "CREATE INDEX IF NOT EXISTS spool_new ON spool (tenant, received_ms, id) WHERE attempts=0");
      sql.execute(
          "CREATE INDEX IF NOT EXISTS spool_retry ON spool (tenant, retry_ms, id) WHERE attempts>0");
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS spool_totals (
            tenant TEXT PRIMARY KEY, events INTEGER NOT NULL DEFAULT 0,
            bytes INTEGER NOT NULL DEFAULT 0, accepted INTEGER NOT NULL DEFAULT 0,
            accepted_bytes INTEGER NOT NULL DEFAULT 0, duplicates INTEGER NOT NULL DEFAULT 0,
            delivered INTEGER NOT NULL DEFAULT 0, backpressure INTEGER NOT NULL DEFAULT 0,
            rejected INTEGER NOT NULL DEFAULT 0
          )
          """);
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS rejection_totals (
            tenant TEXT NOT NULL, stage TEXT NOT NULL, reason TEXT NOT NULL,
            count INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (tenant, stage, reason)
          )
          """);
    } catch (SQLException failure) {
      db.close();
      throw failure;
    }
    return new GatewaySpool(db, limit);
  }

  List<Admission> admit(UUID tenant, List<Reading> readings) throws SQLException {
    return transaction(
        () -> {
          String scope = tenant.toString();
          var totals = stats(tenant);
          long available = limit - totals.bytes();
          int accepted = 0;
          int duplicated = 0;
          int paused = 0;
          int rejected = 0;
          long bytes = 0;
          var results = new ArrayList<Admission>(readings.size());
          try (var existing =
                  db.prepareStatement(
                      "SELECT fingerprint FROM spool WHERE tenant=? AND sensor=? AND event=?");
              var insert =
                  db.prepareStatement(
                      """
              INSERT INTO spool (tenant, device, sensor, event, payload, fingerprint, received_ms)
              VALUES (?, ?, ?, ?, ?, ?, ?)
              """)) {
            for (var reading : readings) {
              existing.setString(1, scope);
              existing.setString(2, reading.sensorId());
              existing.setString(3, reading.eventId());
              try (var prior = existing.executeQuery()) {
                if (prior.next()) {
                  if (reading.fingerprint().equals(ByteString.copyFrom(prior.getBytes(1)))) {
                    duplicated++;
                    results.add(new Admission(TelemetryReceipt.Outcome.DUPLICATE, ""));
                  } else {
                    rejected++;
                    results.add(new Admission(TelemetryReceipt.Outcome.REJECTED, "event-conflict"));
                  }
                  continue;
                }
              }
              if (reading.payload().size() > available) {
                paused++;
                results.add(new Admission(TelemetryReceipt.Outcome.RETRYABLE, "spool-full"));
                continue;
              }
              insert.setString(1, scope);
              insert.setString(2, reading.deviceId());
              insert.setString(3, reading.sensorId());
              insert.setString(4, reading.eventId());
              insert.setBytes(5, reading.payload().toByteArray());
              insert.setBytes(6, reading.fingerprint().toByteArray());
              insert.setLong(7, reading.receivedMs());
              insert.executeUpdate();
              int size = reading.payload().size();
              available -= size;
              bytes += size;
              accepted++;
              results.add(new Admission(TelemetryReceipt.Outcome.ACCEPTED, ""));
            }
          }
          addTotals(scope, accepted, bytes, accepted, bytes, duplicated, 0, paused, rejected);
          if (rejected > 0) {
            recordRejection(scope, "mqtt", "event-conflict", rejected);
          }
          return results;
        });
  }

  void invalid(UUID tenant, String reason) throws SQLException {
    transaction(
        () -> {
          addTotals(tenant.toString(), 0, 0, 0, 0, 0, 0, 0, 1);
          recordRejection(tenant.toString(), "mqtt", reason, 1);
          return null;
        });
  }

  List<Queued> next(UUID tenant, long now, int limit, boolean preferReplay) throws SQLException {
    // Reserve space for both recent first attempts and older or retried rows.
    var live = selectNew(tenant, now, true, Math.max(limit / 2, 1), 0);
    var replay = selectReplay(tenant, now, Math.max(limit - live.size(), 1));
    if (limit == 1 && !live.isEmpty() && !replay.isEmpty()) {
      // With an upstream one-entry limit, alternate the single slot.
      return preferReplay ? replay : live;
    }
    var ready = new ArrayList<>(live);
    ready.addAll(replay);
    if (ready.size() < limit) {
      ready.addAll(selectNew(tenant, now, true, limit - ready.size(), live.size()));
    }
    return ready;
  }

  private List<Queued> selectNew(UUID tenant, long now, boolean live, int count, int offset)
      throws SQLException {
    String sql =
        live
            ? """
            SELECT id, device, sensor, payload, received_ms, attempts FROM spool
            WHERE tenant=? AND attempts=0 AND received_ms>=?
            ORDER BY received_ms, id LIMIT ? OFFSET ?
            """
            : """
            SELECT id, device, sensor, payload, received_ms, attempts FROM spool
            WHERE tenant=? AND attempts=0 AND received_ms<?
            ORDER BY received_ms, id LIMIT ? OFFSET ?
            """;
    try (var query = db.prepareStatement(sql)) {
      query.setString(1, tenant.toString());
      query.setLong(2, now - LIVE_WINDOW_MS);
      query.setInt(3, count);
      query.setInt(4, offset);
      return readRows(query);
    }
  }

  private List<Queued> selectReplay(UUID tenant, long now, int count) throws SQLException {
    var older = selectNew(tenant, now, false, count, 0);
    try (var query =
        db.prepareStatement(
            """
        SELECT id, device, sensor, payload, received_ms, attempts FROM spool
        WHERE tenant=? AND attempts>0 AND retry_ms<=?
        ORDER BY retry_ms, id LIMIT ?
        """)) {
      query.setString(1, tenant.toString());
      query.setLong(2, now);
      query.setInt(3, count);
      return Stream.concat(older.stream(), readRows(query).stream())
          .sorted(Comparator.comparingLong(Queued::receivedMs).thenComparingLong(Queued::id))
          .limit(count)
          .toList();
    }
  }

  private static List<Queued> readRows(java.sql.PreparedStatement query) throws SQLException {
    try (var rows = query.executeQuery()) {
      var result = new ArrayList<Queued>();
      while (rows.next()) {
        result.add(
            new Queued(
                rows.getLong(1),
                rows.getString(2),
                rows.getString(3),
                ByteString.copyFrom(rows.getBytes(4)),
                rows.getLong(5),
                rows.getInt(6)));
      }
      return result;
    }
  }

  void retry(UUID tenant, List<Queued> events, long now) throws SQLException {
    transaction(
        () -> {
          try (var update =
              db.prepareStatement(
                  "UPDATE spool SET retry_ms=?, attempts=attempts+1 WHERE tenant=? AND id=?")) {
            for (var event : events) {
              long delay = Math.min(60_000, 1_000L << Math.min(event.attempts(), 6));
              update.setLong(1, now + delay + Math.floorMod(event.id(), 251));
              update.setString(2, tenant.toString());
              update.setLong(3, event.id());
              update.addBatch();
            }
            update.executeBatch();
          }
          return null;
        });
  }

  void finish(UUID tenant, List<Delivered> deliveries) throws SQLException {
    transaction(
        () -> {
          String scope = tenant.toString();
          int count = 0;
          int rejected = 0;
          long bytes = 0;
          try (var delete =
              db.prepareStatement(
                  "DELETE FROM spool WHERE tenant=? AND id=? RETURNING length(payload)")) {
            for (var delivery : deliveries) {
              delete.setString(1, scope);
              delete.setLong(2, delivery.id());
              try (var row = delete.executeQuery()) {
                if (!row.next()) {
                  continue;
                }
                count++;
                bytes += row.getInt(1);
              }
              if (!delivery.rejection().isEmpty()) {
                rejected++;
                recordRejection(scope, "backend", delivery.rejection(), 1);
              }
            }
          }
          addTotals(scope, -count, -bytes, 0, 0, 0, count - rejected, 0, rejected);
          return null;
        });
  }

  long capacity() {
    return limit;
  }

  Stats stats(UUID tenant) throws SQLException {
    try (var query =
        db.prepareStatement(
            """
        SELECT events, bytes, accepted, accepted_bytes, duplicates, delivered, backpressure, rejected,
          (SELECT min(received_ms) FROM spool WHERE tenant=?) AS oldest_ms
        FROM spool_totals WHERE tenant=?
        """)) {
      query.setString(1, tenant.toString());
      query.setString(2, tenant.toString());
      try (var row = query.executeQuery()) {
        return row.next()
            ? new Stats(
                row.getLong(1),
                row.getLong(2),
                row.getLong(3),
                row.getLong(4),
                row.getLong(5),
                row.getLong(6),
                row.getLong(7),
                row.getLong(8),
                row.getLong(9))
            : new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);
      }
    }
  }

  private void addTotals(
      String tenant,
      long events,
      long bytes,
      long accepted,
      long acceptedBytes,
      long duplicates,
      long delivered,
      long backpressure,
      long rejected)
      throws SQLException {
    try (var update =
        db.prepareStatement(
            """
        INSERT INTO spool_totals VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant) DO UPDATE SET events=events+excluded.events,
          bytes=bytes+excluded.bytes, accepted=accepted+excluded.accepted,
          accepted_bytes=accepted_bytes+excluded.accepted_bytes,
          duplicates=duplicates+excluded.duplicates, delivered=delivered+excluded.delivered,
          backpressure=backpressure+excluded.backpressure, rejected=rejected+excluded.rejected
        """)) {
      update.setString(1, tenant);
      update.setLong(2, events);
      update.setLong(3, bytes);
      update.setLong(4, accepted);
      update.setLong(5, acceptedBytes);
      update.setLong(6, duplicates);
      update.setLong(7, delivered);
      update.setLong(8, backpressure);
      update.setLong(9, rejected);
      update.executeUpdate();
    }
  }

  private void recordRejection(String tenant, String stage, String reason, int count)
      throws SQLException {
    try (var update =
        db.prepareStatement(
            """
        INSERT INTO rejection_totals VALUES (?, ?, ?, ?)
        ON CONFLICT (tenant, stage, reason) DO UPDATE SET count=count+excluded.count
        """)) {
      update.setString(1, tenant);
      update.setString(2, stage);
      update.setString(3, reason);
      update.setInt(4, count);
      update.executeUpdate();
    }
  }

  private <T> T transaction(SqlAction<T> action) throws SQLException {
    db.setAutoCommit(false);
    try {
      T result = action.run();
      db.commit();
      return result;
    } catch (SQLException | RuntimeException failure) {
      db.rollback();
      throw failure;
    } finally {
      db.setAutoCommit(true);
    }
  }

  void queueAck(UUID tenant, String command, byte[] payload) throws SQLException {
    try (var write =
        db.prepareStatement(
            """
        INSERT INTO command_acks(tenant,command,payload)
        SELECT ?,?,? WHERE (SELECT count(*) FROM command_acks WHERE tenant=?) < 2048
        ON CONFLICT DO NOTHING
        """)) {
      write.setString(1, tenant.toString());
      write.setString(2, command);
      write.setBytes(3, payload);
      write.setString(4, tenant.toString());
      write.executeUpdate();
    }
  }

  CommandAck nextAck(UUID tenant) throws SQLException {
    try (var read =
        db.prepareStatement(
            "SELECT command,payload FROM command_acks WHERE tenant=? ORDER BY rowid LIMIT 1")) {
      read.setString(1, tenant.toString());
      try (var rows = read.executeQuery()) {
        return rows.next() ? new CommandAck(rows.getString(1), rows.getBytes(2)) : null;
      }
    }
  }

  void finishAck(UUID tenant, String command) throws SQLException {
    try (var remove =
        db.prepareStatement("DELETE FROM command_acks WHERE tenant=? AND command=?")) {
      remove.setString(1, tenant.toString());
      remove.setString(2, command);
      remove.executeUpdate();
    }
  }

  record CommandAck(String id, byte[] payload) {}

  @Override
  public void close() throws SQLException {
    db.close();
  }

  private interface SqlAction<T> {
    T run() throws SQLException;
  }

  record Reading(
      String deviceId,
      String sensorId,
      String eventId,
      ByteString payload,
      ByteString fingerprint,
      long receivedMs) {}

  record Admission(TelemetryReceipt.Outcome outcome, String reason) {}

  record Queued(
      long id,
      String deviceId,
      String sensorId,
      ByteString payload,
      long receivedMs,
      int attempts) {}

  record Delivered(long id, String rejection) {}

  record Stats(
      long events,
      long bytes,
      long accepted,
      long acceptedBytes,
      long duplicates,
      long delivered,
      long backpressure,
      long rejected,
      long oldestMs) {}
}
