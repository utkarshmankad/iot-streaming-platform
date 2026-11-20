package iot.platform.backend.ingest;

import static iot.platform.telemetry.TelemetryBatchLimits.CONTENT_TYPE;
import static iot.platform.telemetry.TelemetryBatchLimits.PATH;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import iot.platform.backend.BackendApplication;
import iot.platform.telemetry.TelemetryBatchLimits;
import iot.platform.telemetry.proto.TelemetryBatch;
import iot.platform.telemetry.proto.TelemetryBatchEntry;
import iot.platform.telemetry.proto.TelemetryBatchResponse;
import iot.platform.telemetry.proto.TelemetryBatchResult;
import iot.platform.telemetry.proto.TelemetryBatchResult.Outcome;
import iot.platform.telemetry.proto.TelemetryEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class TelemetryIngestIntegrationTest {
  private static final String TENANT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String TENANT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String DEVICE = "00000000-0000-4000-8000-000000000002";
  private static final String SENSOR = "00000000-0000-4000-8000-000000000006";
  private static final String TOKEN_A = "a".repeat(48);
  private static final String TOKEN_B = "b".repeat(48);
  private static final String MIGRATOR_FIXTURE = "test-migrate-fixture";
  private static final String APP_FIXTURE = "test-app-fixture";

  @TempDir Path directory;

  @Test
  void persistsScopedMixedBatchesAndReplaysAtomicallyOnPinnedTimescale() throws Exception {
    var image =
        DockerImageName.parse("timescale/timescaledb:2.27.0-pg17")
            .asCompatibleSubstituteFor("postgres");
    try (var database = new PostgreSQLContainer(image)) {
      database.start();
      try (var owner =
              DriverManager.getConnection(
                  database.getJdbcUrl(), database.getUsername(), database.getPassword());
          var sql = owner.createStatement()) {
        sql.execute(
            """
            CREATE ROLE iot_migrate LOGIN PASSWORD 'test-migrate-fixture' NOSUPERUSER NOBYPASSRLS;
            CREATE ROLE iot_app LOGIN PASSWORD 'test-app-fixture' NOSUPERUSER NOBYPASSRLS;
            CREATE SCHEMA registry AUTHORIZATION iot_migrate;
            CREATE SCHEMA telemetry AUTHORIZATION iot_migrate;
            CREATE EXTENSION IF NOT EXISTS timescaledb WITH SCHEMA public;
            """);
        provision(database.getJdbcUrl());
        try (var runtime =
                new SpringApplicationBuilder(BackendApplication.class)
                    .profiles("registry")
                    .run(
                        "--server.port=0",
                        "--IOT_REGISTRY_JDBC_URL=" + database.getJdbcUrl(),
                        "--IOT_REGISTRY_APP_PASSWORD=" + APP_FIXTURE);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
          var endpoint =
              URI.create(
                  "http://127.0.0.1:"
                      + runtime.getEnvironment().getProperty("local.server.port")
                      + PATH);
          var commits =
              runtime.getBean(MeterRegistry.class).get("iot.ingest.gateway.to.commit").timer();
          var now = System.currentTimeMillis();
          verifyMixedBatch(http, endpoint, now, owner);
          assertEquals(2, commits.count());
          verifyHttpEnvelope(http, endpoint, now);
          verifyTenantScope(database.getJdbcUrl());
          verifyConcurrentClaims(http, endpoint, now, owner);
          assertEquals(4, commits.count());
          verifyRollbackAndLostResponse(http, endpoint, now, owner);
          assertEquals(5, commits.count());
        }
      }
    }
  }

  private void provision(String jdbc) {
    try (var context =
        new SpringApplicationBuilder(BackendApplication.class)
            .profiles("registry-provision")
            .web(WebApplicationType.NONE)
            .run(
                "--IOT_REGISTRY_JDBC_URL=" + jdbc,
                "--IOT_REGISTRY_MIGRATE_PASSWORD=" + MIGRATOR_FIXTURE,
                "--IOT_REGISTRY_GATEWAY_A_TOKEN=" + TOKEN_A,
                "--IOT_REGISTRY_GATEWAY_B_TOKEN=" + TOKEN_B,
                "--IOT_REGISTRY_ACL=" + directory.resolve("registry.acl"))) {
      assertTrue(context.isActive());
    }
  }

  private static void verifyMixedBatch(HttpClient http, URI endpoint, long now, Connection owner)
      throws Exception {
    var initial = event(1, now - 7 * 86_400_000L, 20.1);
    var normalized =
        initial.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(30, UnknownFieldSet.Field.newBuilder().addVarint(5).build())
                    .build())
            .build();
    var mixed =
        batch(
            reading("1", TENANT_A, initial, now - 20),
            reading("2", TENANT_A, initial, now).toBuilder()
                .setPayload(ByteString.copyFrom(new byte[] {8}))
                .build(),
            reading("3", TENANT_A, normalized, now),
            reading("4", TENANT_A, initial.toBuilder().setObservedAtMs(now).build(), now),
            reading("5", TENANT_B, initial, now),
            reading("6", TENANT_A, initial, now).toBuilder()
                .setDeviceId(new UUID(0, 9).toString())
                .build(),
            reading("7", TENANT_A, initial, now).toBuilder().setSensorId("fixture").build(),
            reading("8", TENANT_A, initial, now).toBuilder()
                .setSensorId(new UUID(0, 9).toString())
                .build(),
            reading("9", TENANT_A, event(8, now, 101), now),
            reading("10", TENANT_A, event(8, now + 180_000, 20), now),
            reading("11", TENANT_A, event(8, now, 20), 0),
            reading("12", TENANT_A, event(8, Long.MIN_VALUE, 20), now),
            reading("13", TENANT_A, event(8, now, 20).toBuilder().setSchemaVersion(2).build(), now),
            reading("14", TENANT_A, initial, now).toBuilder()
                .setPayload(ByteString.copyFrom(new byte[4097]))
                .build());

    var response =
        success(
            http.send(request(endpoint, TOKEN_A, mixed), HttpResponse.BodyHandlers.ofByteArray()));
    assertEquals(
        mixed.getEntriesList().stream().map(TelemetryBatchEntry::getCorrelationId).toList(),
        response.getResultsList().stream().map(TelemetryBatchResult::getCorrelationId).toList());
    assertEquals(
        List.of(
            Outcome.COMMITTED,
            Outcome.REJECTED,
            Outcome.DUPLICATE,
            Outcome.REJECTED,
            Outcome.REJECTED,
            Outcome.REJECTED,
            Outcome.REJECTED,
            Outcome.REJECTED,
            Outcome.REJECTED,
            Outcome.REJECTED,
            Outcome.REJECTED,
            Outcome.REJECTED,
            Outcome.REJECTED,
            Outcome.REJECTED),
        response.getResultsList().stream().map(TelemetryBatchResult::getOutcome).toList());
    assertEquals(
        List.of(
            "",
            "malformed-protobuf",
            "",
            "event-conflict",
            "unassigned-sensor",
            "unassigned-sensor",
            "invalid-topic-identity",
            "unassigned-sensor",
            "invalid-reading",
            "invalid-observation-time",
            "invalid-gateway-receipt-time",
            "invalid-observation-time",
            "unsupported-schema",
            "payload-too-large"),
        response.getResultsList().stream().map(TelemetryBatchResult::getReasonCode).toList());

    var b = batch(reading("1", TENANT_B, initial.toBuilder().setValue(21.3).build(), now));
    assertEquals(Outcome.COMMITTED, submit(http, endpoint, TOKEN_B, b).getResults(0).getOutcome());
    assertEquals(
        Outcome.DUPLICATE,
        submit(
                http,
                endpoint,
                TOKEN_A,
                batch(reading("fresh-correlation", TENANT_A, normalized, now + 100)))
            .getResults(0)
            .getOutcome());
    assertCounts(owner, 2);
    try (var sql = owner.createStatement();
        var rows =
            sql.executeQuery(
                """
            SELECT tenant_id, value, observed_at, gateway_received_at, backend_received_at, payload_bytes
            FROM telemetry.readings ORDER BY tenant_id
            """)) {
      assertTrue(rows.next());
      assertEquals(TENANT_A, rows.getString("tenant_id"));
      assertEquals(20.1, rows.getDouble("value"));
      assertEquals(initial.getObservedAtMs(), millis(rows, "observed_at"));
      assertEquals(now - 20, millis(rows, "gateway_received_at"));
      assertTrue(millis(rows, "backend_received_at") >= now);
      assertEquals(initial.getSerializedSize(), rows.getInt("payload_bytes"));
      assertTrue(rows.next());
      assertEquals(TENANT_B, rows.getString("tenant_id"));
      assertEquals(21.3, rows.getDouble("value"));
    }
    try (var sql = owner.createStatement()) {
      sql.execute(
          "UPDATE registry.sensor_schema_versions SET max_value=30 WHERE type_code='oxygen'");
      assertEquals(
          "registry-reading-range",
          submit(http, endpoint, TOKEN_A, batch(reading("1", TENANT_A, event(9, now, 31), now)))
              .getResults(0)
              .getReasonCode());
      sql.execute(
          "UPDATE registry.sensor_schema_versions SET accepted=false WHERE type_code='oxygen'");
      assertEquals(
          "unsupported-schema",
          submit(http, endpoint, TOKEN_A, batch(reading("1", TENANT_A, initial, now)))
              .getResults(0)
              .getReasonCode());
      sql.execute(
          "UPDATE registry.sensor_schema_versions SET accepted=true,max_value=100 WHERE type_code='oxygen'");
      sql.execute("UPDATE registry.devices SET status='disabled'");
      assertEquals(
          "unassigned-sensor",
          submit(http, endpoint, TOKEN_A, batch(reading("1", TENANT_A, initial, now)))
              .getResults(0)
              .getReasonCode());
      sql.execute("UPDATE registry.devices SET status='active'");
      sql.execute("UPDATE registry.gateways SET status='disabled'");
      assertEquals(
          401,
          http.send(
                  request(endpoint, TOKEN_A, batch(reading("1", TENANT_A, initial, now))),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      sql.execute("UPDATE registry.gateways SET status='active'");
    }
    assertCounts(owner, 2);
  }

  private static void verifyHttpEnvelope(HttpClient http, URI endpoint, long now) throws Exception {
    var example = reading("1", TENANT_A, event(2, now, 20), now);
    var auth =
        http.send(request(endpoint, null, batch(example)), HttpResponse.BodyHandlers.discarding());
    assertEquals(401, auth.statusCode());
    assertEquals("Bearer", auth.headers().firstValue("WWW-Authenticate").orElseThrow());
    assertEquals(
        401,
        http.send(
                request(endpoint, "c".repeat(48), batch(example)),
                HttpResponse.BodyHandlers.discarding())
            .statusCode());
    assertEquals(
        400,
        http.send(
                request(endpoint, TOKEN_A, batch(example, example)),
                HttpResponse.BodyHandlers.discarding())
            .statusCode());
    assertEquals(
        400,
        http.send(request(endpoint, TOKEN_A, batch()), HttpResponse.BodyHandlers.discarding())
            .statusCode());
    assertEquals(
        400,
        http.send(
                request(
                    endpoint,
                    TOKEN_A,
                    batch(example.toBuilder().setCorrelationId("x".repeat(65)).build())),
                HttpResponse.BodyHandlers.discarding())
            .statusCode());
    var malformed =
        raw(endpoint, TOKEN_A, HttpRequest.BodyPublishers.ofByteArray(new byte[] {10, 4}));
    assertEquals(400, http.send(malformed, HttpResponse.BodyHandlers.discarding()).statusCode());
    var over = new TelemetryBatchEntry[TelemetryBatchLimits.MAX_ENTRIES + 1];
    Arrays.fill(over, example);
    assertEquals(
        413,
        http.send(request(endpoint, TOKEN_A, batch(over)), HttpResponse.BodyHandlers.discarding())
            .statusCode());
    byte[] tooBig = new byte[TelemetryBatchLimits.MAX_REQUEST_BYTES + 1];
    for (var body :
        List.of(
            HttpRequest.BodyPublishers.ofByteArray(tooBig),
            HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(tooBig)))) {
      assertEquals(
          413,
          http.send(raw(endpoint, TOKEN_A, body), HttpResponse.BodyHandlers.discarding())
              .statusCode());
    }
  }

  private static void verifyTenantScope(String jdbcUrl) throws Exception {
    try (var app = DriverManager.getConnection(jdbcUrl, "iot_app", APP_FIXTURE);
        var statement = app.createStatement();
        var bind = app.prepareStatement("SELECT set_config('iot.tenant_id', ?, true)")) {
      assertEquals(0, count(statement, "SELECT count(*) FROM telemetry.event_receipts"));
      app.setAutoCommit(false);
      for (String tenant : List.of(TENANT_A, TENANT_B)) {
        bind.setString(1, tenant);
        bind.execute();
        try (var only = statement.executeQuery("SELECT tenant_id FROM telemetry.event_receipts")) {
          assertTrue(only.next());
          assertEquals(tenant, only.getString(1));
          assertFalse(only.next());
        }
        app.commit();
        assertEquals(0, count(statement, "SELECT count(*) FROM telemetry.event_receipts"));
      }
      assertEquals(
          "42501",
          assertThrows(
                  SQLException.class,
                  () -> statement.executeQuery("SELECT count(*) FROM telemetry.readings"))
              .getSQLState());
      app.rollback();
      bind.setString(1, TENANT_A);
      bind.execute();
      try (var badWrite =
          app.prepareStatement(
              """
          INSERT INTO telemetry.event_receipts (tenant_id, sensor_id, event_id, fingerprint)
          VALUES (?::uuid, ?::uuid, ?::uuid, ?)
          """)) {
        badWrite.setString(1, TENANT_B);
        badWrite.setString(2, SENSOR);
        badWrite.setString(3, new UUID(0, 999).toString());
        badWrite.setBytes(4, new byte[32]);
        assertEquals(
            "42501", assertThrows(SQLException.class, badWrite::executeUpdate).getSQLState());
      }
      app.rollback();
    }
  }

  private static void verifyConcurrentClaims(
      HttpClient http, URI endpoint, long now, Connection owner) throws Exception {
    var left = event(10, now, 20.5);
    var right = event(11, now, 21.5);
    var first = batch(reading("1", TENANT_A, left, now), reading("2", TENANT_A, right, now));
    var second =
        batch(
            reading("7", TENANT_A, right, now),
            reading("8", TENANT_A, left.toBuilder().setValue(20.6).build(), now));
    var a =
        http.sendAsync(request(endpoint, TOKEN_A, first), HttpResponse.BodyHandlers.ofByteArray());
    var b =
        http.sendAsync(request(endpoint, TOKEN_A, second), HttpResponse.BodyHandlers.ofByteArray());
    CompletableFuture.allOf(a, b).get(10, TimeUnit.SECONDS);
    var results = new ArrayList<>(success(a.join()).getResultsList());
    results.addAll(success(b.join()).getResultsList());
    assertEquals(2, results.stream().filter(x -> x.getOutcome() == Outcome.COMMITTED).count());
    assertEquals(1, results.stream().filter(x -> x.getOutcome() == Outcome.DUPLICATE).count());
    assertEquals(
        1, results.stream().filter(x -> x.getReasonCode().equals("event-conflict")).count());
    assertCounts(owner, 4);
  }

  private static void verifyRollbackAndLostResponse(
      HttpClient http, URI endpoint, long now, Connection owner) throws Exception {
    var body = batch(reading("1", TENANT_A, event(20, now, 42), now));
    try (var sql = owner.createStatement()) {
      sql.execute(
          "ALTER TABLE telemetry.readings ADD CONSTRAINT fixture_failure CHECK (value <> 42) NOT VALID");
      var failed =
          http.send(request(endpoint, TOKEN_A, body), HttpResponse.BodyHandlers.discarding());
      assertEquals(503, failed.statusCode());
      assertCounts(owner, 4);
      sql.execute("ALTER TABLE telemetry.readings DROP CONSTRAINT fixture_failure");
    }

    var relay = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var written = new CompletableFuture<Outcome>();
    relay.createContext(
        PATH,
        exchange -> {
          try (exchange) {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            var downstream = submit(http, endpoint, TOKEN_A, TelemetryBatch.parseFrom(raw));
            written.complete(downstream.getResults(0).getOutcome());
            // Close the relay socket before it sends the already-committed response to this sender.
          } catch (Exception failure) {
            written.completeExceptionally(failure);
          }
        });
    relay.start();
    try {
      URI lossy = URI.create("http://127.0.0.1:" + relay.getAddress().getPort() + PATH);
      assertThrows(
          IOException.class,
          () -> http.send(request(lossy, TOKEN_A, body), HttpResponse.BodyHandlers.discarding()));
      assertEquals(Outcome.COMMITTED, written.get(5, TimeUnit.SECONDS));
      assertCounts(owner, 5);
      assertEquals(
          Outcome.DUPLICATE, submit(http, endpoint, TOKEN_A, body).getResults(0).getOutcome());
      assertCounts(owner, 5);
    } finally {
      relay.stop(0);
    }
    try (var statement = owner.createStatement()) {
      assertEquals(
          1,
          count(
              statement,
              """
          SELECT count(*) FROM timescaledb_information.dimensions
          WHERE hypertable_schema='telemetry' AND hypertable_name='readings'
            AND column_name='observed_at' AND time_interval=interval '1 day'
          """));
      assertEquals(
          2,
          count(
              statement,
              """
          SELECT count(*) FROM timescaledb_information.chunks
          WHERE hypertable_schema='telemetry' AND hypertable_name='readings'
          """));
    }
  }

  private static long millis(java.sql.ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant().toEpochMilli();
  }

  private static void assertCounts(Connection owner, int expected) throws SQLException {
    try (var statement = owner.createStatement()) {
      assertEquals(expected, count(statement, "SELECT count(*) FROM telemetry.readings"));
      assertEquals(expected, count(statement, "SELECT count(*) FROM telemetry.event_receipts"));
    }
  }

  private static int count(java.sql.Statement statement, String query) throws SQLException {
    try (var rows = statement.executeQuery(query)) {
      assertTrue(rows.next());
      return rows.getInt(1);
    }
  }

  private static TelemetryBatchResponse submit(
      HttpClient client, URI endpoint, String token, TelemetryBatch payload)
      throws IOException, InterruptedException {
    return success(
        client.send(request(endpoint, token, payload), HttpResponse.BodyHandlers.ofByteArray()));
  }

  private static TelemetryBatchResponse success(HttpResponse<byte[]> response) throws IOException {
    assertEquals(200, response.statusCode());
    assertEquals(CONTENT_TYPE, response.headers().firstValue("Content-Type").orElseThrow());
    return TelemetryBatchResponse.parseFrom(response.body());
  }

  private static HttpRequest request(URI endpoint, String token, TelemetryBatch payload) {
    return raw(endpoint, token, HttpRequest.BodyPublishers.ofByteArray(payload.toByteArray()));
  }

  private static HttpRequest raw(URI endpoint, String token, HttpRequest.BodyPublisher payload) {
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", CONTENT_TYPE)
            .header("Accept", CONTENT_TYPE)
            .POST(payload);
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return request.build();
  }

  private static TelemetryBatch batch(TelemetryBatchEntry... entries) {
    return TelemetryBatch.newBuilder().addAllEntries(List.of(entries)).build();
  }

  private static TelemetryBatchEntry reading(
      String correlation, String tenant, TelemetryEvent event, long received) {
    return TelemetryBatchEntry.newBuilder()
        .setCorrelationId(correlation)
        .setTenantId(tenant)
        .setDeviceId(DEVICE)
        .setSensorId(SENSOR)
        .setPayload(event.toByteString())
        .setGatewayReceivedAtMs(received)
        .build();
  }

  private static TelemetryEvent event(int id, long observed, double value) {
    return TelemetryEvent.newBuilder()
        .setSchemaVersion(1)
        .setSensorId(SENSOR)
        .setEventId(new UUID(0, id).toString())
        .setObservedAtMs(observed)
        .setValue(value)
        .build();
  }
}
