package iot.platform.gateway.ingest;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import iot.platform.gateway.GatewayApplication;
import iot.platform.registry.proto.RegistryDevice;
import iot.platform.registry.proto.RegistrySchema;
import iot.platform.registry.proto.RegistrySensor;
import iot.platform.registry.proto.RegistrySnapshot;
import iot.platform.telemetry.TelemetryBatchLimits;
import iot.platform.telemetry.proto.TelemetryBatch;
import iot.platform.telemetry.proto.TelemetryBatchResponse;
import iot.platform.telemetry.proto.TelemetryBatchResult;
import iot.platform.telemetry.proto.TelemetryEvent;
import iot.platform.telemetry.proto.TelemetryReceipt;
import iot.platform.telemetry.proto.TelemetryReceipt.Outcome;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

class GatewayPipelineTest {
  private static final String A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String GATEWAY = "00000000-0000-4000-8000-000000000001";
  private static final String DEVICE = "00000000-0000-4000-8000-000000000002";
  private static final String SENSOR = "00000000-0000-4000-8000-000000000006";

  @TempDir Path files;

  @Test
  void verifiesAndAcknowledgesOnlyDurableTenantScopedTelemetryAndRecoversHttpDelivery()
      throws Exception {
    var backend = new BackendFixture();
    try (backend;
        var broker =
            new GenericContainer<>("eclipse-mosquitto:2.0.22")
                .withCopyToContainer(
                    Transferable.of("listener 1883 0.0.0.0\nallow_anonymous true\n"),
                    "/mosquitto/config/mosquitto.conf")
                .withExposedPorts(1883)) {
      broker.start();
      String uri = "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
      var sensor = new MqttAsyncClient(uri, "fixture-producer", new MemoryPersistence());
      try {
        sensor.connect().waitForCompletion(5000);
        var receipts = new LinkedBlockingQueue<Received>();
        sensor
            .subscribe(
                new MqttSubscription[] {
                  new MqttSubscription(root(A) + "receipt", 1),
                  new MqttSubscription(root(B) + "receipt", 1)
                },
                null,
                null,
                new IMqttMessageListener[] {
                  (topic, msg) ->
                      receipts.add(
                          new Received(topic, TelemetryReceipt.parseFrom(msg.getPayload()))),
                  (topic, msg) ->
                      receipts.add(
                          new Received(topic, TelemetryReceipt.parseFrom(msg.getPayload())))
                },
                null)
            .waitForCompletion(5000);

        var first = reading(1, 20.1);
        var second = first.toBuilder().setValue(20.8).build();
        long beforeIngress = System.currentTimeMillis();
        try (var app = start(uri, backend)) {
          await(
              () ->
                  Files.isRegularFile(files.resolve("registry/demo-a.pb"))
                      && Files.isRegularFile(files.resolve("registry/demo-b.pb")));
          var durable = publishAndWait(sensor, receipts, A, first);
          assertEquals(Outcome.ACCEPTED, durable.getOutcome());
          assertEquals(
              ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(first.toByteArray())),
              durable.getFingerprint());
          assertEquals(first.toByteString(), readStored(A));
          assertEquals(Outcome.DUPLICATE, publishAndWait(sensor, receipts, A, first).getOutcome());
          var conflict = publishAndWait(sensor, receipts, A, second);
          assertEquals(Outcome.REJECTED, conflict.getOutcome());
          assertEquals("event-conflict", conflict.getReasonCode());
          assertEquals(Outcome.ACCEPTED, publishAndWait(sensor, receipts, B, second).getOutcome());
          assertEquals(second.toByteString(), readStored(B));

          var invalid = publishAndWait(sensor, receipts, A, reading(2, 22));
          assertEquals(Outcome.REJECTED, invalid.getOutcome());
          assertEquals("registry-reading-range", invalid.getReasonCode());
          var wrongSensor =
              reading(3, 20.2).toBuilder().setSensorId(new UUID(0, 8).toString()).build();
          assertEquals(
              "sensor-mismatch", publishAndWait(sensor, receipts, A, wrongSensor).getReasonCode());
          sensor.publish(root(A) + "telemetry", new byte[] {8}, 1, false).waitForCompletion(5000);
          sensor.publish(root(A) + "telemetry", new byte[4097], 1, false).waitForCompletion(5000);
          sensor
              .publish(
                  "iot/v1/" + A + "/" + new UUID(0, 10) + "/" + SENSOR + "/telemetry",
                  reading(4, 20.3).toByteArray(),
                  1,
                  false)
              .waitForCompletion(5000);
          await(() -> total(app, A, "iot.gateway.rejected.events") >= 6);
          await(() -> backend.seen.containsKey(A) && backend.seen.containsKey(B));
          assertEquals(0, total(app, A, "iot.gateway.delivered.events"));
        }

        backend.registryAvailable.set(false);
        backend.ready.set(true);
        try (var app = start(uri, backend)) {
          await(
              () ->
                  total(app, A, "iot.gateway.delivered.events") == 1
                      && total(app, B, "iot.gateway.delivered.events") == 1);
          var a = backend.seen.get(A).getEntries(0);
          var b = backend.seen.get(B).getEntries(0);
          assertEquals(first.toByteString(), a.getPayload());
          assertEquals(second.toByteString(), b.getPayload());
          assertEquals(DEVICE, a.getDeviceId());
          assertEquals(SENSOR, a.getSensorId());
          assertTrue(a.getGatewayReceivedAtMs() >= beforeIngress);
          assertTrue(b.getGatewayReceivedAtMs() >= beforeIngress);
          assertEquals(
              0,
              app.getBean(MeterRegistry.class)
                  .get("iot.gateway.spool.events")
                  .tag("tenant", A)
                  .gauge()
                  .value());
        }
      } finally {
        if (sensor.isConnected()) {
          sensor.disconnect().waitForCompletion(3000);
        }
        sensor.close(true);
      }
    }
  }

  private ConfigurableApplicationContext start(String mqtt, BackendFixture backend) {
    return new SpringApplicationBuilder(GatewayApplication.class)
        .profiles("registry")
        .run(
            "--server.port=0",
            "--IOT_REGISTRY_URL=http://127.0.0.1:"
                + backend.server.getAddress().getPort()
                + "/internal/v1/registry",
            "--IOT_REGISTRY_GATEWAY_A_TOKEN=gateway-http-a-fixture",
            "--IOT_REGISTRY_GATEWAY_B_TOKEN=gateway-http-b-fixture",
            "--IOT_REGISTRY_CACHE=" + files.resolve("registry"),
            "--IOT_GATEWAY_MQTT_URI=" + mqtt,
            "--IOT_GATEWAY_MQTT_A_PASSWORD=gateway-mqtt-a-fixture",
            "--IOT_GATEWAY_MQTT_B_PASSWORD=gateway-mqtt-b-fixture",
            "--IOT_GATEWAY_SPOOL=" + files.resolve("spool.sqlite"));
  }

  private static TelemetryReceipt publishAndWait(
      MqttAsyncClient client,
      LinkedBlockingQueue<Received> receipts,
      String tenant,
      TelemetryEvent event)
      throws Exception {
    for (int attempt = 0; attempt < 8; attempt++) {
      client
          .publish(root(tenant) + "telemetry", event.toByteArray(), 1, false)
          .waitForCompletion(5000);
      var received = receipts.poll(800, TimeUnit.MILLISECONDS);
      if (received != null
          && received.topic().equals(root(tenant) + "receipt")
          && received.receipt().getEventId().equals(event.getEventId())) {
        return received.receipt();
      }
    }
    return fail("No application receipt for the assigned MQTT topic");
  }

  private ByteString readStored(String tenant) throws Exception {
    try (var sql = DriverManager.getConnection("jdbc:sqlite:" + files.resolve("spool.sqlite"));
        var query = sql.prepareStatement("SELECT payload FROM spool WHERE tenant=?")) {
      query.setString(1, tenant);
      try (var rows = query.executeQuery()) {
        assertTrue(rows.next());
        return ByteString.copyFrom(rows.getBytes(1));
      }
    }
  }

  private static TelemetryEvent reading(int id, double value) {
    return TelemetryEvent.newBuilder()
        .setSchemaVersion(1)
        .setSensorId(SENSOR)
        .setEventId(new UUID(0, id).toString())
        .setValue(value)
        .setObservedAtMs(System.currentTimeMillis())
        .build();
  }

  private static double total(ConfigurableApplicationContext app, String tenant, String name) {
    return app.getBean(MeterRegistry.class)
        .get(name)
        .tag("tenant", tenant)
        .functionCounter()
        .count();
  }

  private static String root(String tenant) {
    return "iot/v1/" + tenant + "/" + DEVICE + "/" + SENSOR + "/";
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < until) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    fail("Condition did not become true before the timeout");
  }

  private record Received(String topic, TelemetryReceipt receipt) {}

  private static final class BackendFixture implements AutoCloseable {
    private final HttpServer server;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean registryAvailable = new AtomicBoolean(true);
    private final ConcurrentHashMap<String, TelemetryBatch> seen = new ConcurrentHashMap<>();

    private BackendFixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/internal/v1/registry", this::registry);
      server.createContext(TelemetryBatchLimits.PATH, this::batch);
      server.start();
    }

    private void registry(HttpExchange exchange) throws IOException {
      try (exchange) {
        if (!registryAvailable.get()) {
          exchange.sendResponseHeaders(503, -1);
          return;
        }
        var tenant = authorizedTenant(exchange);
        var schema =
            RegistrySchema.newBuilder()
                .setVersion(1)
                .setValidatorRevision("1")
                .setProtobufContract("iot.telemetry.v1.TelemetryEvent")
                .setMinValue(0)
                .setMaxValue(21);
        byte[] snapshot =
            RegistrySnapshot.newBuilder()
                .setFormatVersion(1)
                .setTenantId(tenant)
                .setGatewayId(GATEWAY)
                .addDevices(
                    RegistryDevice.newBuilder()
                        .setDeviceId(DEVICE)
                        .setBrokerClientId("fixture-producer")
                        .addSensors(
                            RegistrySensor.newBuilder()
                                .setSensorId(SENSOR)
                                .setTypeCode("oxygen")
                                .setUnit("% vol")
                                .setSamplingPeriodMs(1000)
                                .addAcceptedSchemas(schema)))
                .build()
                .toByteArray();
        try {
          String digest =
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot));
          exchange.getResponseHeaders().set("ETag", "\"" + digest + "\"");
        } catch (java.security.NoSuchAlgorithmException unavailable) {
          throw new IOException(unavailable);
        }
        exchange.getResponseHeaders().set("Content-Type", TelemetryBatchLimits.CONTENT_TYPE);
        exchange.sendResponseHeaders(200, snapshot.length);
        exchange.getResponseBody().write(snapshot);
      }
    }

    private void batch(HttpExchange exchange) throws IOException {
      try (exchange) {
        var tenant = authorizedTenant(exchange);
        var batch = TelemetryBatch.parseFrom(exchange.getRequestBody());
        if (tenant.isEmpty()
            || batch.getEntriesList().stream()
                .anyMatch(entry -> !tenant.equals(entry.getTenantId()))) {
          exchange.sendResponseHeaders(403, -1);
          return;
        }
        seen.put(tenant, batch);
        if (!ready.get()) {
          exchange.sendResponseHeaders(503, -1);
          return;
        }
        var response = TelemetryBatchResponse.newBuilder();
        batch
            .getEntriesList()
            .forEach(
                entry ->
                    response.addResults(
                        TelemetryBatchResult.newBuilder()
                            .setCorrelationId(entry.getCorrelationId())
                            .setOutcome(TelemetryBatchResult.Outcome.COMMITTED)));
        var bytes = response.build().toByteArray();
        exchange.getResponseHeaders().set("Content-Type", TelemetryBatchLimits.CONTENT_TYPE);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    }

    private static String authorizedTenant(HttpExchange exchange) {
      return switch (exchange.getRequestHeaders().getFirst("Authorization")) {
        case "Bearer gateway-http-a-fixture" -> A;
        case "Bearer gateway-http-b-fixture" -> B;
        case null, default -> "";
      };
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
