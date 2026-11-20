package iot.platform.gateway.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import iot.platform.registry.proto.RegistryDevice;
import iot.platform.registry.proto.RegistrySchema;
import iot.platform.registry.proto.RegistrySensor;
import iot.platform.registry.proto.RegistrySnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryCacheTest {
  private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID GATEWAY = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final String TOKEN = "a".repeat(48);
  private static final String TOKEN_B = "b".repeat(48);

  @TempDir Path directory;
  private HttpServer server;
  private URI endpoint;
  private final AtomicReference<Reply> reply = new AtomicReference<>();
  private final AtomicReference<String> version = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/v1/registry",
        exchange -> {
          try (exchange) {
            version.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            Reply result = reply.get();
            if (("Bearer " + TOKEN_B)
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              result = Reply.good(snapshot().setTenantId(TENANT_B.toString()).build());
            }
            if (result.etag() != null) {
              exchange.getResponseHeaders().set("ETag", result.etag());
            }
            exchange.sendResponseHeaders(
                result.code(), result.body().length == 0 ? -1 : result.body().length);
            if (result.body().length != 0) {
              exchange.getResponseBody().write(result.body());
            }
          }
        });
    server.start();
    endpoint =
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1/registry");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void persistsVerifiesAndReusesTheLatestScopedSnapshot() throws Exception {
    Path file = directory.resolve("cache.pb");
    var expected = snapshot().build();
    try (var http = HttpClient.newHttpClient()) {
      var cache = cache(http, file);
      assertTrue(cache.current().isEmpty());
      reply.set(new Reply(304, null, new byte[0]));
      assertThrows(IOException.class, cache::refresh);
      var valid = Reply.good(expected);
      reply.set(valid);
      cache.refresh();
      assertEquals(expected, cache.current().orElseThrow());
      assertNull(version.get());
      assertEquals(expected, cache(http, file).current().orElseThrow());
      reply.set(new Reply(304, valid.etag(), new byte[0]));
      cache.refresh();
      assertEquals(valid.etag(), version.get());
      reply.set(new Reply(503, null, new byte[0]));
      assertThrows(IOException.class, cache::refresh);
      assertEquals(expected, cache.current().orElseThrow());

      reply.set(new Reply(401, null, new byte[0]));
      assertThrows(IOException.class, cache::refresh);
      assertTrue(cache.current().isEmpty());
      assertFalse(Files.exists(file));
      Files.write(file, new byte[] {-1});
      assertTrue(cache(http, file).current().isEmpty());
    }
  }

  @Test
  void rejectsInvalidVersionsScopesShapesAndChecksumsWithoutReplacingGoodData() throws Exception {
    Path file = directory.resolve("cache.pb");
    var expected = snapshot().build();
    try (var http = HttpClient.newHttpClient()) {
      var cache = cache(http, file);
      var valid = Reply.good(expected);
      reply.set(valid);
      cache.refresh();
      var variants =
          java.util.List.of(
              new Reply(200, null, valid.body()),
              new Reply(200, "wrong", valid.body()),
              new Reply(200, valid.etag(), new byte[1_048_577]),
              Reply.good(snapshot().setFormatVersion(2).build()),
              Reply.good(snapshot().setTenantId(TENANT_B.toString()).build()),
              Reply.good(snapshot().setGatewayId(TENANT_B.toString()).build()),
              Reply.good(
                  snapshot()
                      .setDevices(0, expected.getDevices(0).toBuilder().setDeviceId("invalid"))
                      .build()),
              Reply.good(snapshot().addDevices(expected.getDevices(0)).build()),
              Reply.good(
                  snapshot()
                      .setDevices(0, expected.getDevices(0).toBuilder().clearBrokerClientId())
                      .build()),
              Reply.good(
                  snapshot()
                      .setDevices(
                          0,
                          expected.getDevices(0).toBuilder()
                              .addSensors(expected.getDevices(0).getSensors(0)))
                      .build()),
              withSensor(expected.getDevices(0).getSensors(0).toBuilder().clearTypeCode()),
              withSensor(expected.getDevices(0).getSensors(0).toBuilder().clearUnit()),
              withSensor(expected.getDevices(0).getSensors(0).toBuilder().setSamplingPeriodMs(0)),
              withSchema(schema().setVersion(0)),
              withSensor(
                  expected.getDevices(0).getSensors(0).toBuilder().addAcceptedSchemas(schema())),
              withSchema(schema().clearProtobufContract()),
              withSchema(schema().clearValidatorRevision()),
              withSchema(schema().setMinValue(Double.NaN)),
              withSchema(schema().setMaxValue(Double.POSITIVE_INFINITY)),
              withSchema(schema().setMinValue(50).setMaxValue(10)));
      for (var invalid : variants) {
        reply.set(invalid);
        assertThrows(IOException.class, cache::refresh);
        assertEquals(expected, cache.current().orElseThrow());
      }
      assertEquals(expected, cache(http, file).current().orElseThrow());
    }
  }

  @Test
  void scheduledCompositionRefreshesBothDemoTenantsAndToleratesOneUnavailable() throws Exception {
    var registries = new GatewayRegistries(endpoint, TOKEN, TOKEN_B, directory);
    reply.set(new Reply(503, null, new byte[0]));
    registries.refresh();
    assertFalse(Files.exists(directory.resolve("demo-a.pb")));
    assertTrue(Files.isRegularFile(directory.resolve("demo-b.pb")));
    reply.set(Reply.good(snapshot().build()));
    registries.refresh();
    assertTrue(Files.isRegularFile(directory.resolve("demo-a.pb")));
  }

  private RegistryCache cache(HttpClient http, Path file) {
    return new RegistryCache(http, endpoint, TOKEN, TENANT, GATEWAY, file);
  }

  private static Reply withSensor(RegistrySensor.Builder sensor) {
    var snapshot = snapshot();
    snapshot.getDevicesBuilder(0).setSensors(0, sensor);
    return Reply.good(snapshot.build());
  }

  private static Reply withSchema(RegistrySchema.Builder schema) {
    var snapshot = snapshot();
    snapshot.getDevicesBuilder(0).getSensorsBuilder(0).setAcceptedSchemas(0, schema);
    return Reply.good(snapshot.build());
  }

  private static RegistrySnapshot.Builder snapshot() {
    return RegistrySnapshot.newBuilder()
        .setFormatVersion(1)
        .setTenantId(TENANT.toString())
        .setGatewayId(GATEWAY.toString())
        .addDevices(
            RegistryDevice.newBuilder()
                .setDeviceId("00000000-0000-4000-8000-000000000002")
                .setBrokerClientId("demo")
                .addSensors(
                    RegistrySensor.newBuilder()
                        .setSensorId("00000000-0000-4000-8000-000000000003")
                        .setTypeCode("temperature")
                        .setUnit("°C")
                        .setSamplingPeriodMs(1000)
                        .addAcceptedSchemas(schema())));
  }

  private static RegistrySchema.Builder schema() {
    return RegistrySchema.newBuilder()
        .setVersion(1)
        .setProtobufContract("telemetry")
        .setValidatorRevision("1")
        .setMinValue(0)
        .setMaxValue(100);
  }

  private record Reply(int code, String etag, byte[] body) {
    static Reply good(RegistrySnapshot snapshot) {
      byte[] bytes = snapshot.toByteArray();
      try {
        var version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new Reply(200, "\"" + version + "\"", bytes);
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    }
  }
}
