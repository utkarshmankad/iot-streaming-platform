package iot.platform.backend.ingest;

import iot.platform.backend.registry.GatewayAccess;
import iot.platform.control.CommandProtocol;
import iot.platform.telemetry.HeartbeatBatchLimits;
import iot.platform.telemetry.TelemetryBatchLimits;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.SensorHeartbeatBatch;
import iot.platform.telemetry.proto.SensorHeartbeatObservation;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("registry")
class HeartbeatController {
  private final GatewayAccess access;
  private final JdbcTemplate jdbc;

  HeartbeatController(GatewayAccess access, JdbcTemplate jdbc) {
    this.access = access;
    this.jdbc = jdbc;
  }

  @PostMapping(value = HeartbeatBatchLimits.PATH, consumes = TelemetryBatchLimits.CONTENT_TYPE)
  @Transactional(timeout = 5)
  public ResponseEntity<Void> observe(
      HttpServletRequest request,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    var assignment = access.authorize(GatewayAccess.bearerToken(authorization));
    if (assignment.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    long now = System.currentTimeMillis();
    SensorHeartbeatBatch batch;
    try {
      byte[] bytes = request.getInputStream().readNBytes(HeartbeatBatchLimits.MAX_BYTES + 1);
      if (bytes.length > HeartbeatBatchLimits.MAX_BYTES) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
      }
      batch = SensorHeartbeatBatch.parseFrom(bytes);
    } catch (IOException invalid) {
      return ResponseEntity.badRequest().build();
    }
    if (batch.getObservationsCount() > HeartbeatBatchLimits.MAX_OBSERVATIONS) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }

    var observations =
        batch.getObservationsList().stream()
            .filter(item -> valid(item, now))
            .sorted(Comparator.comparing(item -> item.getHeartbeat().getSensorId()))
            .toList();
    var scope = assignment.get();
    if (!observations.isEmpty()) {
      jdbc.batchUpdate(
          """
          INSERT INTO registry.sensor_state
            (tenant_id,sensor_id,last_heartbeat_at,last_heartbeat_source_at)
          SELECT s.tenant_id,s.sensor_id,?,?
          FROM registry.sensors s JOIN registry.devices d USING (tenant_id,device_id)
          WHERE s.tenant_id=? AND s.sensor_id=? AND d.device_id=? AND d.gateway_id=?
            AND s.status='active' AND d.status='active'
          ON CONFLICT (tenant_id,sensor_id) DO UPDATE
          SET last_heartbeat_at=greatest(registry.sensor_state.last_heartbeat_at,excluded.last_heartbeat_at),
              last_heartbeat_source_at=excluded.last_heartbeat_source_at
          WHERE registry.sensor_state.last_heartbeat_source_at IS NULL
            OR registry.sensor_state.last_heartbeat_source_at < excluded.last_heartbeat_source_at
          """,
          observations,
          HeartbeatBatchLimits.MAX_OBSERVATIONS,
          (sql, item) -> {
            long source = item.getHeartbeat().getObservedAtMs();
            sql.setObject(1, Instant.ofEpochMilli(Math.min(source, now)).atOffset(ZoneOffset.UTC));
            sql.setObject(2, Instant.ofEpochMilli(source).atOffset(ZoneOffset.UTC));
            sql.setObject(3, scope.tenant());
            sql.setObject(4, UUID.fromString(item.getHeartbeat().getSensorId()));
            sql.setObject(5, UUID.fromString(item.getDeviceId()));
            sql.setObject(6, scope.gateway());
          });
    }
    return ResponseEntity.noContent().build();
  }

  private static boolean valid(SensorHeartbeatObservation item, long now) {
    var heartbeat = item.getHeartbeat();
    return item.hasHeartbeat()
        && heartbeat.getSchemaVersion() == TelemetryValidation.VERSION
        && CommandProtocol.canonicalUuid(heartbeat.getSensorId())
        && CommandProtocol.canonicalUuid(item.getDeviceId())
        && heartbeat.getObservedAtMs() > now - HeartbeatBatchLimits.OFFLINE_MS
        && heartbeat.getObservedAtMs() <= now + HeartbeatBatchLimits.FUTURE_MS
        && heartbeat.getAppliedCommandVersion() >= 0;
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  ResponseEntity<Void> unavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
  }
}
