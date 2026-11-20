package iot.platform.backend.ingest;

import iot.platform.backend.registry.GatewayAccess.Assignment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
@Profile("registry")
class TelemetryStore {
  private final JdbcClient jdbc;
  private final JdbcTemplate writer;

  TelemetryStore(JdbcClient jdbc, JdbcTemplate writer) {
    this.jdbc = jdbc;
    this.writer = writer;
  }

  Map<UUID, Sensor> sensors(Assignment assignment, Set<UUID> sensors, int version) {
    var registered = new HashMap<UUID, Sensor>();
    if (sensors.isEmpty()) {
      return registered;
    }
    jdbc.sql(
            """
        SELECT s.sensor_id, s.device_id, s.type_code,
          v.protobuf_contract, v.validator_revision, v.min_value, v.max_value
        FROM registry.sensors s
        JOIN registry.devices d ON d.tenant_id = s.tenant_id AND d.device_id = s.device_id
        LEFT JOIN registry.sensor_schema_versions v
          ON v.type_code = s.type_code AND v.schema_version = ? AND v.accepted
        WHERE s.tenant_id = ? AND d.gateway_id = ? AND s.sensor_id = ANY (CAST(? AS uuid[]))
          AND s.status = 'active' AND d.status = 'active'
        """)
        .param(version)
        .param(assignment.tenant())
        .param(assignment.gateway())
        .param(new SqlArrayValue("uuid", sensors.toArray(UUID[]::new)))
        .query(
            (row, index) -> {
              registered.put(
                  row.getObject("sensor_id", UUID.class),
                  new Sensor(
                      row.getObject("device_id", UUID.class),
                      row.getString("type_code"),
                      row.getString("protobuf_contract"),
                      row.getString("validator_revision"),
                      row.getDouble("min_value"),
                      row.getDouble("max_value")));
              return null;
            })
        .list();
    return registered;
  }

  Set<Key> claim(UUID tenant, Collection<Reading> firstOccurrences) {
    var claimed =
        jdbc.sql(
                """
        INSERT INTO telemetry.event_receipts (tenant_id, sensor_id, event_id, fingerprint)
        SELECT ?, input.sensor_id, input.event_id, decode(input.fingerprint, 'hex')
        FROM unnest(CAST(? AS uuid[]), CAST(? AS uuid[]), CAST(? AS text[]))
          AS input(sensor_id, event_id, fingerprint)
        ORDER BY input.sensor_id, input.event_id
        ON CONFLICT (tenant_id, sensor_id, event_id) DO NOTHING
        RETURNING sensor_id, event_id
        """)
            .param(tenant)
            .param(
                new SqlArrayValue(
                    "uuid",
                    firstOccurrences.stream().map(r -> r.key().sensor()).toArray(UUID[]::new)))
            .param(
                new SqlArrayValue(
                    "uuid",
                    firstOccurrences.stream().map(r -> r.key().event()).toArray(UUID[]::new)))
            .param(
                new SqlArrayValue(
                    "text",
                    firstOccurrences.stream().map(Reading::fingerprint).toArray(String[]::new)))
            .query((row, number) -> key(row))
            .list();
    return new HashSet<>(claimed);
  }

  Map<Key, String> fingerprints(UUID tenant, List<Key> keys) {
    var found = new HashMap<Key, String>();
    if (keys.isEmpty()) {
      return found;
    }
    // READ COMMITTED takes a fresh snapshot after any competing claim has resolved.
    jdbc.sql(
            """
        SELECT ledger.sensor_id, ledger.event_id, encode(ledger.fingerprint, 'hex') AS fingerprint
        FROM telemetry.event_receipts ledger
        JOIN unnest(CAST(? AS uuid[]), CAST(? AS uuid[])) AS lookup(sensor_id, event_id)
          ON ledger.sensor_id = lookup.sensor_id AND ledger.event_id = lookup.event_id
        WHERE ledger.tenant_id = ?
        """)
        .param(new SqlArrayValue("uuid", keys.stream().map(Key::sensor).toArray(UUID[]::new)))
        .param(new SqlArrayValue("uuid", keys.stream().map(Key::event).toArray(UUID[]::new)))
        .param(tenant)
        .query(
            (row, number) -> {
              found.put(key(row), row.getString("fingerprint"));
              return null;
            })
        .list();
    if (found.size() != keys.size()) {
      throw new org.springframework.dao.DataRetrievalFailureException(
          "A resolved receipt key is unavailable");
    }
    return found;
  }

  void insert(UUID tenant, List<Reading> readings, long backendReceivedMs) {
    OffsetDateTime backendReceived = utc(backendReceivedMs);
    writer.batchUpdate(
        """
        INSERT INTO telemetry.readings
          (tenant_id, sensor_id, event_id, observed_at, gateway_received_at,
           backend_received_at, schema_version, value, payload_bytes)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        readings,
        250,
        (sql, reading) -> {
          sql.setObject(1, tenant);
          sql.setObject(2, reading.key().sensor());
          sql.setObject(3, reading.key().event());
          sql.setObject(4, utc(reading.observedMs()));
          sql.setObject(5, utc(reading.gatewayReceivedMs()));
          sql.setObject(6, backendReceived);
          sql.setInt(7, reading.schemaVersion());
          sql.setDouble(8, reading.value());
          sql.setInt(9, reading.payloadBytes());
        });
  }

  private static Key key(ResultSet row) throws SQLException {
    return new Key(row.getObject("sensor_id", UUID.class), row.getObject("event_id", UUID.class));
  }

  private static OffsetDateTime utc(long millis) {
    return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC);
  }

  record Key(UUID sensor, UUID event) {}

  record Sensor(
      UUID device, String type, String contract, String validator, double min, double max) {}

  record Reading(
      Key key,
      String fingerprint,
      long observedMs,
      long gatewayReceivedMs,
      int schemaVersion,
      double value,
      int payloadBytes) {}
}
