package iot.platform.simulator.payload;

import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.TelemetryEvent;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import java.util.UUID;

final class SensorReadings {
  private SensorReadings() {}

  static TelemetryEvent sample(SensorStream stream, long seed, long sequence, long observedMs) {
    var random =
        new SplittableRandom(
            seed
                ^ stream.device().tenantId().hashCode()
                ^ ((long) stream.sensorId().hashCode() << 32)
                ^ sequence);
    double cycle = Math.sin(2 * Math.PI * Math.floorMod(observedMs, 86_400_000) / 86_400_000.0);
    double noise = random.nextDouble(-1, 1);
    double reading =
        switch (stream.type()) {
          case TEMPERATURE -> 22 + 4 * cycle + noise * .5;
          case HUMIDITY -> 55 - 12 * cycle + noise * 2;
          case PRESSURE -> 1013 + 6 * cycle + noise;
          case PM25 -> 15 + 6 * cycle + noise * 3;
          case LIGHT -> Math.max(0, 18000 * cycle + noise * 120);
          case OXYGEN -> 20.9 + .1 * cycle + noise * .05;
        };
    String identity = seed + ":" + stream.sensorId() + ":" + sequence + ":" + observedMs;
    var event =
        TelemetryEvent.newBuilder()
            .setSchemaVersion(TelemetryValidation.VERSION)
            .setSensorId(stream.sensorId().toString())
            .setEventId(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.US_ASCII)).toString())
            .setObservedAtMs(observedMs)
            .setValue(reading)
            .build();
    TelemetryValidation.validate(event, stream.sensorId(), stream.type(), observedMs);
    return event;
  }
}
