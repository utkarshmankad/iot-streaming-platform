package iot.platform.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import iot.platform.telemetry.proto.TelemetryEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

class TelemetryValidationTest {
  private static final UUID SENSOR = new UUID(0, 1);
  private static final long NOW = 1_800_000_000_000L;

  @ParameterizedTest
  @CsvFileSource(resources = "/sensor-readings.csv")
  void validatesEachReadingType(String code, double valid, double invalid) {
    var type = SensorType.fromCode(code);
    var event = event().setValue(valid).build();
    assertEquals(event, TelemetryValidation.decode(event.toByteArray(), SENSOR, type, NOW));
    assertEquals(
        "invalid-reading",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    TelemetryValidation.validate(
                        event().setValue(invalid).build(), SENSOR, type, NOW))
            .getMessage());
  }

  @Test
  void rejectsMalformedAndOversizedWirePayloads() {
    assertEquals(
        "malformed-protobuf",
        assertThrows(
                IllegalArgumentException.class,
                () -> TelemetryValidation.decode(new byte[] {8}, SENSOR, SensorType.OXYGEN, NOW))
            .getMessage());
    assertEquals(
        "payload-too-large",
        assertThrows(
                IllegalArgumentException.class,
                () -> TelemetryValidation.decode(new byte[4097], SENSOR, SensorType.OXYGEN, NOW))
            .getMessage());
    assertThrows(IllegalArgumentException.class, () -> SensorType.fromCode("unregistered"));
  }

  @Test
  void requiresCanonicalIdentityAndRequiredWireFields() {
    invalid("unsupported-schema", event().clearSchemaVersion());
    invalid("unsupported-schema", event().setSchemaVersion(2));
    invalid("sensor-mismatch", event().setSensorId(new UUID(0, 2).toString()));
    invalid("invalid-event-id", event().setEventId("1-2-3-4-5"));
    invalid("invalid-event-id", event().setEventId("invalid"));
    invalid("invalid-observation-time", event().clearObservedAtMs());
    invalid("invalid-observation-time", event().setObservedAtMs(NOW + 120_001));
    invalid("invalid-reading", event().clearValue());
    invalid("invalid-reading", event().setValue(Double.NaN));
    invalid("invalid-reading", event().setValue(Double.POSITIVE_INFINITY));
    TelemetryValidation.validate(
        event().setObservedAtMs(NOW + 120_000).build(), SENSOR, SensorType.HUMIDITY, NOW);
    TelemetryValidation.validate(
        event().setObservedAtMs(NOW - 7 * 86_400_000L).build(), SENSOR, SensorType.HUMIDITY, NOW);
  }

  private static void invalid(String reason, TelemetryEvent.Builder builder) {
    assertEquals(
        reason,
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    TelemetryValidation.validate(builder.build(), SENSOR, SensorType.HUMIDITY, NOW))
            .getMessage());
  }

  private static TelemetryEvent.Builder event() {
    return TelemetryEvent.newBuilder()
        .setSchemaVersion(1)
        .setSensorId(SENSOR.toString())
        .setEventId(new UUID(0, 3).toString())
        .setObservedAtMs(NOW)
        .setValue(50);
  }
}
