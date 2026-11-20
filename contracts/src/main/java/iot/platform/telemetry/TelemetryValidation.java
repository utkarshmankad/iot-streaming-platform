package iot.platform.telemetry;

import com.google.protobuf.InvalidProtocolBufferException;
import iot.platform.telemetry.proto.TelemetryEvent;
import java.util.UUID;

public final class TelemetryValidation {
  public static final int VERSION = 1;
  public static final int MAX_PAYLOAD_BYTES = 4096;

  private TelemetryValidation() {}

  public static TelemetryEvent decode(
      byte[] payload, UUID expectedSensor, SensorType type, long nowMs) {
    if (payload.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("payload-too-large");
    }
    try {
      var event = TelemetryEvent.parseFrom(payload);
      validate(event, expectedSensor, type, nowMs);
      return event;
    } catch (InvalidProtocolBufferException malformed) {
      throw new IllegalArgumentException("malformed-protobuf", malformed);
    }
  }

  public static void validate(
      TelemetryEvent event, UUID expectedSensor, SensorType type, long nowMs) {
    if (!event.hasSchemaVersion() || event.getSchemaVersion() != VERSION) {
      throw new IllegalArgumentException("unsupported-schema");
    }
    if (!expectedSensor.toString().equals(event.getSensorId())) {
      throw new IllegalArgumentException("sensor-mismatch");
    }
    if (!canonicalUuid(event.getEventId())) {
      throw new IllegalArgumentException("invalid-event-id");
    }
    if (!event.hasObservedAtMs() || event.getObservedAtMs() > nowMs + 120_000) {
      throw new IllegalArgumentException("invalid-observation-time");
    }
    if (!event.hasValue() || !type.accepts(event.getValue())) {
      throw new IllegalArgumentException("invalid-reading");
    }
  }

  private static boolean canonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
