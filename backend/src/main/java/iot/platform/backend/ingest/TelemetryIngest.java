package iot.platform.backend.ingest;

import iot.platform.backend.registry.GatewayAccess;
import iot.platform.backend.registry.GatewayAccess.Assignment;
import iot.platform.telemetry.SensorType;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.TelemetryBatch;
import iot.platform.telemetry.proto.TelemetryBatchEntry;
import iot.platform.telemetry.proto.TelemetryBatchResponse;
import iot.platform.telemetry.proto.TelemetryBatchResult;
import iot.platform.telemetry.proto.TelemetryBatchResult.Outcome;
import iot.platform.telemetry.proto.TelemetryEvent;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("registry")
class TelemetryIngest {
  private static final String CONTRACT = "iot.telemetry.v1.TelemetryEvent";
  private static final String VALIDATOR = "1";
  // Leave room for TimescaleDB to align the first supported timestamp to a complete daily chunk.
  private static final long EARLIEST_OBSERVATION =
      Instant.parse("-004712-01-03T00:00:00Z").toEpochMilli();
  private final GatewayAccess access;
  private final TelemetryStore store;

  TelemetryIngest(GatewayAccess access, TelemetryStore store) {
    this.access = access;
    this.store = store;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
  public Optional<TelemetryBatchResponse> ingest(
      String token, TelemetryBatch batch, long receivedMs) {
    return access.authorize(token).map(assignment -> ingest(assignment, batch, receivedMs));
  }

  private TelemetryBatchResponse ingest(
      Assignment assignment, TelemetryBatch batch, long receivedMs) {
    var response = TelemetryBatchResponse.newBuilder();
    var entries = new ArrayList<ScopedEntry>();
    var sensors = new HashSet<UUID>();
    for (var entry : batch.getEntriesList()) {
      var result = response.addResultsBuilder().setCorrelationId(entry.getCorrelationId());
      if (!entry.getTenantId().equals(assignment.tenant().toString())) {
        rejected(result, "unassigned-sensor");
        continue;
      }
      UUID sensor = canonicalUuid(entry.getSensorId());
      UUID device = canonicalUuid(entry.getDeviceId());
      if (sensor == null || device == null) {
        rejected(result, "invalid-topic-identity");
        continue;
      }
      sensors.add(sensor);
      entries.add(new ScopedEntry(sensor, device, entry, result));
    }

    var registered = store.sensors(assignment, sensors, TelemetryValidation.VERSION);
    var valid = new ArrayList<Validated>();
    for (var entry : entries) {
      var reading = validate(entry, registered.get(entry.sensor()), receivedMs);
      if (reading != null) {
        valid.add(new Validated(reading, entry.result()));
      }
    }
    if (!valid.isEmpty()) {
      persist(assignment.tenant(), valid, receivedMs);
    }
    return response.build();
  }

  private static TelemetryStore.Reading validate(
      ScopedEntry scoped, TelemetryStore.Sensor registration, long receivedMs) {
    var entry = scoped.entry();
    if (registration == null || !registration.device().equals(scoped.device())) {
      rejected(scoped.result(), "unassigned-sensor");
      return null;
    }
    if (!CONTRACT.equals(registration.contract()) || !VALIDATOR.equals(registration.validator())) {
      rejected(scoped.result(), "unsupported-schema");
      return null;
    }
    long gatewayReceived = entry.getGatewayReceivedAtMs();
    if (gatewayReceived <= 0 || gatewayReceived > receivedMs + 120_000) {
      rejected(scoped.result(), "invalid-gateway-receipt-time");
      return null;
    }
    TelemetryEvent event;
    try {
      var type = SensorType.fromCode(registration.type());
      event =
          TelemetryValidation.decode(
              entry.getPayload().toByteArray(), scoped.sensor(), type, receivedMs);
    } catch (IllegalArgumentException invalid) {
      rejected(scoped.result(), invalid.getMessage());
      return null;
    }
    if (event.getObservedAtMs() < EARLIEST_OBSERVATION) {
      rejected(scoped.result(), "invalid-observation-time");
      return null;
    }
    if (event.getValue() < registration.min() || event.getValue() > registration.max()) {
      rejected(scoped.result(), "registry-reading-range");
      return null;
    }
    var key = new TelemetryStore.Key(scoped.sensor(), UUID.fromString(event.getEventId()));
    return new TelemetryStore.Reading(
        key,
        fingerprint(key, event),
        event.getObservedAtMs(),
        gatewayReceived,
        event.getSchemaVersion(),
        normalizedValue(event.getValue()),
        entry.getPayload().size());
  }

  private void persist(UUID tenant, List<Validated> valid, long receivedMs) {
    var first = new LinkedHashMap<TelemetryStore.Key, TelemetryStore.Reading>();
    for (var item : valid) {
      first.putIfAbsent(item.reading().key(), item.reading());
    }
    var claimed = store.claim(tenant, first.values());
    var prior =
        store.fingerprints(
            tenant, first.keySet().stream().filter(key -> !claimed.contains(key)).toList());
    if (!claimed.isEmpty()) {
      store.insert(
          tenant,
          first.values().stream().filter(item -> claimed.contains(item.key())).toList(),
          receivedMs);
    }

    for (var item : valid) {
      var reading = item.reading();
      var key = reading.key();
      if (claimed.remove(key)) {
        item.result().setOutcome(Outcome.COMMITTED);
      } else {
        String canonical = prior.getOrDefault(key, first.get(key).fingerprint());
        if (canonical.equals(reading.fingerprint())) {
          item.result().setOutcome(Outcome.DUPLICATE);
        } else {
          rejected(item.result(), "event-conflict");
        }
      }
    }
  }

  static String fingerprint(TelemetryStore.Key key, TelemetryEvent event) {
    // Telemetry v1 canonical order: version, sensor UUID, event UUID, observed epoch ms, value.
    byte[] fields =
        ByteBuffer.allocate(52)
            .putInt(event.getSchemaVersion())
            .putLong(key.sensor().getMostSignificantBits())
            .putLong(key.sensor().getLeastSignificantBits())
            .putLong(key.event().getMostSignificantBits())
            .putLong(key.event().getLeastSignificantBits())
            .putLong(event.getObservedAtMs())
            .putDouble(normalizedValue(event.getValue()))
            .array();
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fields));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required SHA-256 algorithm unavailable", impossible);
    }
  }

  private static double normalizedValue(double value) {
    return value == 0.0 ? 0.0 : value;
  }

  private static UUID canonicalUuid(String value) {
    try {
      var uuid = UUID.fromString(value);
      return uuid.toString().equals(value) ? uuid : null;
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static void rejected(TelemetryBatchResult.Builder response, String reason) {
    response.setOutcome(Outcome.REJECTED).setReasonCode(reason);
  }

  private record ScopedEntry(
      UUID sensor, UUID device, TelemetryBatchEntry entry, TelemetryBatchResult.Builder result) {}

  private record Validated(TelemetryStore.Reading reading, TelemetryBatchResult.Builder result) {}
}
