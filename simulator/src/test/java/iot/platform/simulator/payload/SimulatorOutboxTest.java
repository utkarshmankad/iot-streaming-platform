package iot.platform.simulator.payload;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import iot.platform.telemetry.SensorType;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.TelemetryReceipt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulatorOutboxTest {
  private static final long NOW = 1_800_000_000_000L;
  private static final long SEED = 17;

  @TempDir Path directory;

  @Test
  void retriesOriginalBytesAfterRestartAndRequiresMatchingTerminalReceipt() throws Exception {
    var source = stream(1, 2, SensorType.TEMPERATURE);
    var otherTenant = stream(2, 2, SensorType.TEMPERATURE);
    var wrongDevice = stream(1, 3, SensorType.TEMPERATURE);
    Path file = directory.resolve("nested/outbox.sqlite");
    var intent = new SimulatorOutbox.Intent(source, NOW);
    SimulatorOutbox.Pending first;
    try (var outbox = SimulatorOutbox.open(file, 10_000)) {
      outbox.generate(List.of(intent, intent, new SimulatorOutbox.Intent(otherTenant, NOW)), SEED);
      first = outbox.due(source.device(), NOW, 1).getFirst();
      assertTrue(outbox.due(source.device(), NOW + 1, 1).isEmpty());
      assertEquals(0, first.attempts());
      assertEquals(1, outbox.stats(source.device().tenantId()).generated());
      assertEquals(1, outbox.stats(otherTenant.device().tenantId()).pendingEvents());
    }
    try (var outbox = SimulatorOutbox.open(file, 10_000)) {
      outbox.generate(List.of(intent), SEED);
      var retry = outbox.due(source.device(), NOW + 3000, 1).getFirst();
      assertArrayEquals(first.payload(), retry.payload());
      assertEquals(first.eventId(), retry.eventId());
      assertEquals(1, retry.attempts());
      var receipt = receipt(retry);
      assertFalse(outbox.acknowledge(source, receipt.setSchemaVersion(2).build()));
      receipt.setSchemaVersion(1);
      assertFalse(
          outbox.acknowledge(
              source, receipt.setOutcome(TelemetryReceipt.Outcome.RETRYABLE).build()));
      receipt.setOutcome(TelemetryReceipt.Outcome.DUPLICATE);
      assertFalse(outbox.acknowledge(wrongDevice, receipt.build()));
      assertFalse(outbox.acknowledge(otherTenant, receipt.build()));
      assertFalse(
          outbox.acknowledge(source, receipt(retry).setFingerprint(ByteString.EMPTY).build()));
      assertTrue(outbox.acknowledge(source, receipt.build()));
      assertFalse(outbox.acknowledge(source, receipt.build()));
      var stats = outbox.stats(source.device().tenantId());
      assertEquals(0, stats.pendingBytes());
      assertEquals(1, stats.accepted());
      assertEquals(first.payload().length, stats.generatedBytes());
      assertEquals(1, outbox.stats(otherTenant.device().tenantId()).pendingEvents());
    }
  }

  @Test
  void capsEachTenantSeparatelyAndDoesNotRestoreDroppedSlots() throws Exception {
    var first = stream(1, 2, SensorType.LIGHT);
    var second = stream(2, 2, SensorType.OXYGEN);
    var file = directory.resolve("outbox.sqlite");
    assertThrows(IllegalArgumentException.class, () -> SimulatorOutbox.open(file, 0));
    try (var outbox = SimulatorOutbox.open(file, 100)) {
      var dropped = new SimulatorOutbox.Intent(first, NOW + 1000);
      outbox.generate(
          List.of(
              new SimulatorOutbox.Intent(first, NOW),
              dropped,
              new SimulatorOutbox.Intent(second, NOW)),
          SEED);
      var a = outbox.stats(first.device().tenantId());
      assertEquals(2, a.generated());
      assertEquals(1, a.dropped());
      assertEquals(1, a.pendingEvents());
      assertEquals(1, outbox.stats(second.device().tenantId()).pendingEvents());
      assertEquals(0, outbox.stats(new UUID(0, 5)).pendingEvents());
      var event = outbox.due(first.device(), NOW, 1).getFirst();
      assertTrue(
          outbox.acknowledge(
              first, receipt(event).setOutcome(TelemetryReceipt.Outcome.REJECTED).build()));
      outbox.generate(List.of(dropped), SEED);
      assertEquals(1, outbox.stats(first.device().tenantId()).rejected());
      assertEquals(0, outbox.stats(first.device().tenantId()).pendingEvents());
    }
  }

  @Test
  void deterministicSamplesAndScopedAssignmentsCoverAllTypes() throws Exception {
    var file = directory.resolve("simulator.tsv");
    var entries = new StringBuilder();
    for (var type : SensorType.values()) {
      entries
          .append(new UUID(0, 1))
          .append('\t')
          .append(new UUID(0, 2))
          .append("\tclient-one\tdevice-a\t")
          .append(new UUID(0, type.ordinal() + 10))
          .append('\t')
          .append(type.name().toLowerCase(java.util.Locale.ROOT))
          .append("\t1000\n");
    }
    Files.writeString(file, entries);
    var streams = SensorStream.read(file);
    assertEquals(6, streams.size());
    for (var stream : streams) {
      long slot = stream.observationSlot(NOW);
      assertTrue(slot <= NOW);
      assertEquals(1000, stream.observationSlot(NOW + 1000) - slot);
      var sample = SensorReadings.sample(stream, SEED, 1, slot);
      assertEquals(sample, SensorReadings.sample(stream, SEED, 1, slot));
      assertNotEquals(
          sample.getEventId(), SensorReadings.sample(stream, SEED, 1, slot + 1000).getEventId());
      assertEquals(
          sample,
          TelemetryValidation.decode(sample.toByteArray(), stream.sensorId(), stream.type(), NOW));
      assertTrue(stream.topic("telemetry").endsWith("/" + stream.sensorId() + "/telemetry"));
    }
    Files.writeString(file, "unexpected");
    assertThrows(IllegalArgumentException.class, () -> SensorStream.read(file));
    Files.writeString(file, entries.toString() + entries);
    assertThrows(IllegalArgumentException.class, () -> SensorStream.read(file));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SensorStream(streams.getFirst().device(), new UUID(0, 4), SensorType.LIGHT, 0));
  }

  static SensorStream stream(long tenant, long device, SensorType type) {
    return new SensorStream(
        new SensorStream.Device(
            new UUID(0, tenant), new UUID(0, device), "client-" + tenant, "device-a"),
        new UUID(0, 3),
        type,
        1000);
  }

  static TelemetryReceipt.Builder receipt(SimulatorOutbox.Pending event) {
    return TelemetryReceipt.newBuilder()
        .setSchemaVersion(1)
        .setEventId(event.eventId())
        .setFingerprint(ByteString.copyFrom(SimulatorOutbox.fingerprint(event.payload())))
        .setOutcome(TelemetryReceipt.Outcome.ACCEPTED);
  }
}
