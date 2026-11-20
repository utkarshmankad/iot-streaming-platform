package iot.platform.simulator.payload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.MeterRegistry;
import iot.platform.simulator.SimulatorApplication;
import iot.platform.telemetry.SensorType;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.TelemetryEvent;
import iot.platform.telemetry.proto.TelemetryHeartbeat;
import iot.platform.telemetry.proto.TelemetryReceipt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

class SimulationMqttTest {
  @TempDir Path directory;

  @Test
  void publishesTelemetryAndHeartbeatAndReplaysUntilGatewayAcknowledges() throws Exception {
    try (var broker =
        new GenericContainer<>("eclipse-mosquitto:2.0.22")
            .withCopyToContainer(
                Transferable.of("listener 1883 0.0.0.0\nallow_anonymous true\n"),
                "/mosquitto/config/mosquitto.conf")
            .withExposedPorts(1883)) {
      broker.start();
      String uri = "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
      var device =
          new SensorStream.Device(new UUID(0, 1), new UUID(0, 2), "fixture-device", "device-a");
      var stream = new SensorStream(device, new UUID(0, 3), SensorType.OXYGEN, 1000);
      var file = directory.resolve("simulator.tsv");
      Path database = directory.resolve("outbox.sqlite");
      Files.writeString(
          file,
          String.join(
                  "\t",
                  device.tenantId().toString(),
                  device.deviceId().toString(),
                  device.clientId(),
                  device.principal(),
                  stream.sensorId().toString(),
                  "oxygen",
                  "1000")
              + "\n");

      var messages = new LinkedBlockingQueue<Reading>();
      var heartbeats = new LinkedBlockingQueue<MqttMessage>();
      var gateway = new MqttAsyncClient(uri, "fixture-gateway", new MemoryPersistence());
      try {
        gateway.connect().waitForCompletion(5000);
        try {
          gateway
              .subscribe(
                  new MqttSubscription[] {
                    new MqttSubscription(stream.topic("telemetry"), 1),
                    new MqttSubscription(stream.topic("heartbeat"), 0)
                  },
                  null,
                  null,
                  new IMqttMessageListener[] {
                    (topic, message) ->
                        messages.add(
                            new Reading(message, TelemetryEvent.parseFrom(message.getPayload()))),
                    (topic, message) -> heartbeats.add(message)
                  },
                  null)
              .waitForCompletion(5000);

          try (var app =
              new SpringApplicationBuilder(SimulatorApplication.class)
                  .profiles("simulate")
                  .run(
                      "--server.port=0",
                      "--IOT_SIM_MQTT_URI=" + uri,
                      "--IOT_SIM_ASSIGNMENTS=" + file,
                      "--IOT_SIM_OUTBOX=" + database,
                      "--IOT_SIM_DEVICE_A_PASSWORD=mqtt-test-fixture")) {
            var first = awaitReading(messages, unused -> true);
            var event = first.event();
            assertEquals(1, first.message().getQos());
            assertFalse(first.message().isRetained());
            assertEquals(
                event,
                TelemetryValidation.decode(
                    first.message().getPayload(),
                    stream.sensorId(),
                    stream.type(),
                    System.currentTimeMillis()));
            assertEquals(
                1000,
                awaitReading(
                            messages,
                            reading -> reading.event().getObservedAtMs() > event.getObservedAtMs())
                        .event()
                        .getObservedAtMs()
                    - event.getObservedAtMs());
            var heartbeatMessage = heartbeats.poll(8, TimeUnit.SECONDS);
            assertNotNull(heartbeatMessage);
            assertEquals(0, heartbeatMessage.getQos());
            var heartbeat = TelemetryHeartbeat.parseFrom(heartbeatMessage.getPayload());
            assertEquals(stream.sensorId().toString(), heartbeat.getSensorId());
            assertTrue(heartbeat.getEnabled());

            String receiptTopic = stream.topic("receipt");
            var matching =
                TelemetryReceipt.newBuilder()
                    .setSchemaVersion(1)
                    .setEventId(event.getEventId())
                    .setFingerprint(
                        ByteString.copyFrom(
                            SimulatorOutbox.fingerprint(first.message().getPayload())));
            gateway.publish(receiptTopic, new byte[] {8}, 1, false).waitForCompletion(3000);
            gateway
                .publish(
                    receiptTopic,
                    matching.setOutcome(TelemetryReceipt.Outcome.ACCEPTED).build().toByteArray(),
                    0,
                    false)
                .waitForCompletion(3000);
            gateway
                .publish(
                    receiptTopic,
                    matching.setOutcome(TelemetryReceipt.Outcome.RETRYABLE).build().toByteArray(),
                    1,
                    false)
                .waitForCompletion(3000);
            var retry =
                awaitReading(
                    messages, reading -> reading.event().getEventId().equals(event.getEventId()));
            assertArrayEquals(first.message().getPayload(), retry.message().getPayload());
            gateway
                .publish(
                    receiptTopic,
                    matching.setOutcome(TelemetryReceipt.Outcome.ACCEPTED).build().toByteArray(),
                    1,
                    false)
                .waitForCompletion(3000);
            var accepted =
                app.getBean(MeterRegistry.class)
                    .get("iot.simulator.accepted.events")
                    .tag("tenant", device.tenantId().toString())
                    .functionCounter();
            await(() -> accepted.count() == 1);
          }
          try (var persisted = SimulatorOutbox.open(database, 5000)) {
            var stats = persisted.stats(device.tenantId());
            assertEquals(1, stats.accepted());
            assertTrue(stats.pendingEvents() >= 1);
            assertTrue(stats.generatedBytes() > stats.pendingBytes());
          }
        } finally {
          gateway.disconnect().waitForCompletion(3000);
        }
      } finally {
        gateway.close(true);
      }
    }
  }

  private static Reading awaitReading(
      LinkedBlockingQueue<Reading> messages, Predicate<Reading> matches)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (System.nanoTime() < deadline) {
      var reading = messages.poll(100, TimeUnit.MILLISECONDS);
      if (reading != null && matches.test(reading)) {
        return reading;
      }
    }
    return fail("Timed out waiting for MQTT telemetry");
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    fail("Timed out waiting for an application receipt to update the outbox");
  }

  private record Reading(MqttMessage message, TelemetryEvent event) {}
}
