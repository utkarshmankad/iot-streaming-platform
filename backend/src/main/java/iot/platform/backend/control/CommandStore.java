package iot.platform.backend.control;

import iot.platform.backend.registry.GatewayAccess.Assignment;
import iot.platform.control.CommandProtocol;
import iot.platform.control.proto.PendingCommand;
import iot.platform.control.proto.PendingCommands;
import iot.platform.control.proto.SamplingAcknowledgment;
import iot.platform.control.proto.SamplingCommand;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("registry")
public class CommandStore {
  private final JdbcClient jdbc;

  CommandStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Command> create(
      UUID tenant, UUID operator, UUID sensor, String key, boolean enabled, long ttlMs) {
    var target =
        jdbc.sql(
                """
                SELECT d.device_id, d.gateway_id
                FROM registry.sensors s JOIN registry.devices d USING (tenant_id, device_id)
                JOIN registry.gateways g USING (tenant_id, gateway_id)
                WHERE s.tenant_id=? AND s.sensor_id=? AND s.status='active'
                  AND d.status='active' AND g.status='active'
                """)
            .params(tenant, sensor)
            .query((row, index) -> new Target(id(row, "device_id"), id(row, "gateway_id")))
            .optional();
    if (target.isEmpty()) {
      return Optional.empty();
    }
    jdbc.sql(
            """
            INSERT INTO registry.sensor_state(tenant_id,sensor_id) VALUES (?,?)
            ON CONFLICT DO NOTHING
            """)
        .params(tenant, sensor)
        .update();
    long last = lockSensor(tenant, sensor);
    var existing =
        jdbc.sql(
                """
                SELECT * FROM registry.commands
                WHERE tenant_id=? AND operator_id=? AND sensor_id=? AND idempotency_key=?
                """)
            .params(tenant, operator, sensor, key)
            .query(CommandStore::read)
            .optional();
    if (existing.isPresent()) {
      return existing;
    }

    Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
    jdbc.sql(
            """
            UPDATE registry.commands
            SET status=CASE WHEN expires_at<=? THEN 'expired' ELSE 'superseded' END,
                resolved_at=least(expires_at, ?),
                reason_code=CASE WHEN expires_at<=? THEN 'COMMAND_EXPIRED' ELSE 'NEWER_COMMAND' END
            WHERE tenant_id=? AND sensor_id=? AND status='pending'
            """)
        .params(utc(now), utc(now), utc(now), tenant, sensor)
        .update();
    long version = Math.addExact(last, 1);
    jdbc.sql(
            """
            UPDATE registry.sensor_state SET command_version=?, desired_enabled=?
            WHERE tenant_id=? AND sensor_id=?
            """)
        .params(version, enabled, tenant, sensor)
        .update();
    return Optional.of(
        jdbc.sql(
                """
                INSERT INTO registry.commands
                  (tenant_id,command_id,sensor_id,device_id,gateway_id,operator_id,idempotency_key,
                   version,requested_enabled,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                """)
            .params(
                tenant,
                UUID.randomUUID(),
                sensor,
                target.get().device(),
                target.get().gateway(),
                operator,
                key,
                version,
                enabled,
                utc(now),
                utc(now.plusMillis(ttlMs)))
            .query(CommandStore::read)
            .single());
  }

  public Optional<Command> find(UUID tenant, UUID command) {
    return jdbc.sql("SELECT * FROM registry.commands WHERE tenant_id=? AND command_id=?")
        .params(tenant, command)
        .query(CommandStore::read)
        .optional();
  }

  public Optional<Command> expireAndRead(UUID tenant, UUID id, Instant now) {
    jdbc.sql(
            """
            UPDATE registry.commands
            SET status='expired',resolved_at=expires_at,reason_code='COMMAND_EXPIRED'
            WHERE tenant_id=? AND command_id=? AND status='pending' AND expires_at<=?
            """)
        .params(tenant, id, utc(now))
        .update();
    return find(tenant, id);
  }

  public PendingCommands pending(Assignment assignment, Instant now) {
    jdbc.sql(
            """
            UPDATE registry.commands
            SET status='expired',resolved_at=expires_at,reason_code='COMMAND_EXPIRED'
            WHERE tenant_id=? AND gateway_id=? AND status='pending' AND expires_at<=?
            """)
        .params(assignment.tenant(), assignment.gateway(), utc(now))
        .update();
    var pending = PendingCommands.newBuilder();
    jdbc.sql(
            """
            SELECT c.* FROM registry.commands c
            JOIN registry.sensors s USING (tenant_id,sensor_id,device_id)
            JOIN registry.devices d USING (tenant_id,device_id,gateway_id)
            WHERE c.tenant_id=? AND c.gateway_id=? AND c.status='pending' AND c.expires_at>?
              AND s.status='active' AND d.status='active'
            ORDER BY c.expires_at,c.command_id LIMIT ?
            """)
        .params(assignment.tenant(), assignment.gateway(), utc(now), CommandProtocol.MAX_PENDING)
        .query(CommandStore::read)
        .list()
        .forEach(
            command ->
                pending.addCommands(
                    PendingCommand.newBuilder()
                        .setDeviceId(command.deviceId().toString())
                        .setCommand(
                            SamplingCommand.newBuilder()
                                .setSchemaVersion(CommandProtocol.VERSION)
                                .setCommandId(command.commandId().toString())
                                .setSensorId(command.sensorId().toString())
                                .setVersion(command.version())
                                .setEnabled(command.requestedEnabled())
                                .setExpiresAtMs(command.expiresAt().toEpochMilli()))));
    return pending.build();
  }

  public AckOutcome acknowledge(
      Assignment assignment, SamplingAcknowledgment ack, Instant received) {
    UUID tenant = assignment.tenant();
    UUID commandId = UUID.fromString(ack.getCommandId());
    var assigned =
        jdbc.sql(
                """
                SELECT c.* FROM registry.commands c
                JOIN registry.sensors s USING (tenant_id,sensor_id,device_id)
                JOIN registry.devices d USING (tenant_id,device_id,gateway_id)
                WHERE c.tenant_id=? AND c.command_id=? AND c.gateway_id=?
                  AND s.status='active' AND d.status='active'
                """)
            .params(tenant, commandId, assignment.gateway())
            .query(CommandStore::read)
            .optional();
    if (assigned.isEmpty()) {
      return AckOutcome.MISSING;
    }
    UUID sensor = assigned.get().sensorId();
    lockSensor(tenant, sensor);
    Command current = find(tenant, commandId).orElseThrow();
    Instant applied = Instant.ofEpochMilli(ack.getAppliedAtMs());
    if (!sensor.toString().equals(ack.getSensorId())
        || ack.getVersion() != current.version()
        || applied.isBefore(current.createdAt())
        || !applied.isBefore(current.expiresAt())
        || applied.isAfter(received)) {
      return AckOutcome.CONFLICT;
    }
    boolean actual = ack.getEnabled();
    if (current.appliedAt() != null) {
      return current.appliedAt().equals(applied) && current.actualEnabled() == actual
          ? AckOutcome.ACCEPTED
          : AckOutcome.CONFLICT;
    }
    String status = current.statusAt(received);
    if ("pending".equals(status)) {
      status = actual == current.requestedEnabled() ? "applied" : "failed";
    }
    Instant resolved =
        current.resolvedAt() != null
            ? current.resolvedAt()
            : "expired".equals(status) ? current.expiresAt() : received;
    jdbc.sql(
            """
            UPDATE registry.commands
            SET status=?,resolved_at=?,reason_code=?,ack_received_at=?,applied_at=?,actual_enabled=?
            WHERE tenant_id=? AND command_id=?
            """)
        .param(status)
        .param(utc(resolved))
        .param(Command.reason(status))
        .param(utc(received))
        .param(utc(applied))
        .param(actual)
        .param(tenant)
        .param(commandId)
        .update();
    jdbc.sql(
            """
            UPDATE registry.sensor_state SET confirmed_enabled=?,applied_version=?
            WHERE tenant_id=? AND sensor_id=? AND applied_version<?
            """)
        .params(actual, current.version(), tenant, sensor, current.version())
        .update();
    return AckOutcome.ACCEPTED;
  }

  private long lockSensor(UUID tenant, UUID sensor) {
    return jdbc.sql(
            """
            SELECT command_version FROM registry.sensor_state
            WHERE tenant_id=? AND sensor_id=? FOR UPDATE
            """)
        .params(tenant, sensor)
        .query(Long.class)
        .single();
  }

  private static Command read(ResultSet row, int number) throws SQLException {
    return new Command(
        id(row, "command_id"),
        id(row, "sensor_id"),
        id(row, "device_id"),
        row.getLong("version"),
        row.getBoolean("requested_enabled"),
        row.getString("status"),
        stamp(row, "created_at"),
        stamp(row, "expires_at"),
        stamp(row, "resolved_at"),
        stamp(row, "applied_at"),
        row.getObject("actual_enabled", Boolean.class));
  }

  private static UUID id(ResultSet row, String name) throws SQLException {
    return row.getObject(name, UUID.class);
  }

  private static Instant stamp(ResultSet row, String name) throws SQLException {
    var stamp = row.getObject(name, OffsetDateTime.class);
    return stamp == null ? null : stamp.toInstant();
  }

  private static OffsetDateTime utc(Instant time) {
    return time.atOffset(ZoneOffset.UTC);
  }

  public enum AckOutcome {
    ACCEPTED,
    CONFLICT,
    MISSING
  }

  private record Target(UUID device, UUID gateway) {}

  public record Command(
      UUID commandId,
      UUID sensorId,
      UUID deviceId,
      long version,
      boolean requestedEnabled,
      String status,
      Instant createdAt,
      Instant expiresAt,
      Instant resolvedAt,
      Instant appliedAt,
      Boolean actualEnabled) {
    public String statusAt(Instant now) {
      return "pending".equals(status) && !now.isBefore(expiresAt) ? "expired" : status;
    }

    public static String reason(String status) {
      return switch (status) {
        case "superseded" -> "NEWER_COMMAND";
        case "expired" -> "COMMAND_EXPIRED";
        case "failed" -> "STATE_MISMATCH";
        default -> null;
      };
    }
  }
}
