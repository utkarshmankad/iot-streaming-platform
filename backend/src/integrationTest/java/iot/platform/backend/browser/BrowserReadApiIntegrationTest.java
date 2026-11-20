package iot.platform.backend.browser;

import static org.junit.jupiter.api.Assertions.*;

import iot.platform.backend.BackendApplication;
import iot.platform.backend.analytics.TrendStorage.Resolution;
import iot.platform.control.CommandProtocol;
import iot.platform.control.proto.PendingCommands;
import iot.platform.control.proto.SamplingAcknowledgment;
import iot.platform.telemetry.HeartbeatBatchLimits;
import iot.platform.telemetry.proto.SensorHeartbeatBatch;
import iot.platform.telemetry.proto.SensorHeartbeatObservation;
import iot.platform.telemetry.proto.TelemetryHeartbeat;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BrowserReadApiIntegrationTest {
  private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID SENSOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID PRIVATE_SENSOR = new UUID(0, 220);
  private static final String TEST_PASSWORD = "browser-test-fixture";
  private static final Duration QUARTER = Duration.ofMinutes(15);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path directory;

  @Test
  void protectsSessionsAndServesOnlyTheActiveTenantsSensorsAndTrends() throws Exception {
    var image =
        DockerImageName.parse("timescale/timescaledb:2.27.0-pg17")
            .asCompatibleSubstituteFor("postgres");
    try (var database = new PostgreSQLContainer(image)) {
      database.start();
      String url = database.getJdbcUrl();
      try (var owner =
          DriverManager.getConnection(url, database.getUsername(), database.getPassword())) {
        initialize(owner);
        provision(url);
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        insertReadings(owner, now);
        try (var runtime =
            new SpringApplicationBuilder(BackendApplication.class)
                .profiles("registry")
                .run(
                    "--server.port=0",
                    "--IOT_REGISTRY_JDBC_URL=" + url,
                    "--IOT_REGISTRY_APP_PASSWORD=test-app-fixture",
                    "--IOT_OPERATOR_A_PASSWORD=" + TEST_PASSWORD,
                    "--IOT_OPERATOR_B_PASSWORD=" + TEST_PASSWORD)) {
          int port = ((WebServerApplicationContext) runtime).getWebServer().getPort();
          try (var a = new Browser(port);
              var b = new Browser(port)) {
            verifyLogin(a);
            String cursor = verifyPagination(a);
            verifyTrends(a, now);
            verifyCommands(a, b, port, owner);
            verifyLiveness(a, b, port, owner);
            verifySwitchAndRevoke(a, b, owner, cursor, now);
          }
        }
      }
    }
  }

  private static void initialize(Connection owner) throws SQLException {
    try (var sql = owner.createStatement()) {
      sql.execute(
          """
          CREATE ROLE iot_migrate LOGIN PASSWORD 'test-migrate-fixture' NOSUPERUSER NOBYPASSRLS;
          CREATE ROLE iot_app LOGIN PASSWORD 'test-app-fixture' NOSUPERUSER NOBYPASSRLS;
          CREATE SCHEMA registry AUTHORIZATION iot_migrate;
          CREATE SCHEMA telemetry AUTHORIZATION iot_migrate;
          CREATE EXTENSION IF NOT EXISTS timescaledb WITH SCHEMA public;
          """);
    }
  }

  private void provision(String url) {
    try (var context =
        new SpringApplicationBuilder(BackendApplication.class)
            .web(WebApplicationType.NONE)
            .profiles("registry-provision")
            .run(
                "--IOT_REGISTRY_JDBC_URL=" + url,
                "--IOT_REGISTRY_MIGRATE_PASSWORD=test-migrate-fixture",
                "--IOT_REGISTRY_GATEWAY_A_TOKEN=" + "a".repeat(48),
                "--IOT_REGISTRY_GATEWAY_B_TOKEN=" + "b".repeat(48),
                "--IOT_REGISTRY_ACL=" + directory.resolve("registry.acl"))) {
      assertTrue(context.isActive());
    }
  }

  private static void insertReadings(Connection db, Instant now) throws SQLException {
    try (var sql = db.createStatement()) {
      assertEquals(
          1,
          sql.executeUpdate(
              """
              INSERT INTO registry.sensors (tenant_id, sensor_id, device_id, type_code, display_name)
              SELECT tenant_id, '%s', device_id, 'temperature', 'Private sensor'
              FROM registry.devices WHERE tenant_id='%s' LIMIT 1
              """
                  .formatted(PRIVATE_SENSOR, B)));
    }
    var observed = Resolution.QUARTER_HOUR.endOf(now).minus(QUARTER).minusSeconds(20);
    try (var write =
        db.prepareStatement(
            """
            WITH receipt AS (
                INSERT INTO telemetry.event_receipts(tenant_id,sensor_id,event_id,fingerprint)
                VALUES (?, ?, ?, decode(repeat('00',32),'hex'))
                RETURNING tenant_id, sensor_id, event_id
            )
            INSERT INTO telemetry.readings(tenant_id,sensor_id,event_id,observed_at,
              gateway_received_at,backend_received_at,schema_version,value,payload_bytes)
            SELECT tenant_id, sensor_id, event_id, ?, clock_timestamp(), clock_timestamp(), 1, ?, 94
            FROM receipt
            """)) {
      for (var tenant : List.of(A, B)) {
        write.setObject(1, tenant);
        write.setObject(2, SENSOR);
        write.setObject(3, new UUID(0, 55));
        write.setObject(4, observed.atOffset(ZoneOffset.UTC));
        write.setDouble(5, tenant.equals(A) ? 12 : 31);
        assertEquals(1, write.executeUpdate());
      }
    }
  }

  private static void verifyLogin(Browser browser) throws Exception {
    assertProblem(browser.get("/sensors"), 401, "AUTHENTICATION_REQUIRED");
    assertProblem(browser.send("POST", "/session", "{}", null), 403, "INVALID_CSRF");
    String csrf = browser.csrf();
    assertProblem(browser.send("POST", "/session", "{", csrf), 400, "INVALID_REQUEST");
    assertProblem(browser.send("POST", "/session", "{}", csrf), 400, "INVALID_CREDENTIALS");
    assertProblem(
        browser.send("POST", "/session", credentials("operator-a", "invalid"), csrf),
        401,
        "AUTHENTICATION_REQUIRED");
    String guestCookie = browser.cookie();
    var login = browser.send("POST", "/session", credentials("operator-a", TEST_PASSWORD), csrf);
    assertEquals(A.toString(), body(login, 200).path("activeTenantId").asText());
    assertEquals(1, body(login, 200).path("tenants").size());
    String cookie = login.headers().firstValue("Set-Cookie").orElseThrow();
    assertTrue(cookie.contains("Path=/api/v1"));
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Strict"));
    assertNotEquals(guestCookie, browser.cookie());
    assertProblem(browser.send("DELETE", "/session", null, csrf), 403, "INVALID_CSRF");
  }

  private static String verifyPagination(Browser browser) throws Exception {
    var first = body(browser.get("/sensors?limit=1"), 200);
    var sensor = first.path("items").get(0);
    assertEquals(SENSOR.toString(), sensor.path("sensorId").asText());
    assertEquals("temperature", sensor.path("type").asText());
    assertEquals("unknown", sensor.path("connectivity").asText());
    assertEquals("stale", sensor.path("sampleStatus").asText());
    assertTrue(sensor.path("confirmedEnabled").isNull());
    assertTrue(Instant.parse(sensor.path("lastObservedAt").asText()).isBefore(Instant.now()));
    String cursor = first.path("nextCursor").asText();
    var next = body(browser.get("/sensors?limit=1&cursor=" + cursor), 200);
    assertNotEquals(SENSOR.toString(), next.path("items").get(0).path("sensorId").asText());
    assertTrue(body(browser.get("/sensors"), 200).path("nextCursor").isNull());
    assertProblem(browser.get("/sensors?limit=201"), 400, "INVALID_LIMIT");
    assertProblem(browser.get("/sensors?limit=text"), 400, "INVALID_REQUEST");
    assertProblem(browser.get("/sensors?cursor=invalid"), 400, "INVALID_CURSOR");
    return cursor;
  }

  private static void verifyTrends(Browser browser, Instant now) throws Exception {
    assertEquals(12, historicalMean(browser, now));
    Instant to = Resolution.QUARTER_HOUR.endOf(now);
    Instant from = to.minus(QUARTER.multipliedBy(2));
    String base = query("15m", from, to);
    var chart = body(browser.get(base + "&sensorId=" + SENSOR), 200);
    var series = chart.path("series").get(0);
    assertEquals("temperature", series.path("type").asText());
    assertEquals("fresh", series.path("freshness").path("state").asText());
    var gap = series.path("buckets").get(1);
    assertEquals(0, gap.path("count").asInt());
    assertTrue(gap.path("mean").isNull());
    assertEquals(
        Instant.parse(chart.path("generatedAt").asText()).isBefore(to),
        gap.path("partial").asBoolean());

    for (var resolution : List.of(Resolution.HOUR, Resolution.DAY, Resolution.WEEK)) {
      Instant end = resolution.endOf(now);
      var start =
          resolution
              .endOf(end.minusSeconds(1))
              .minus(
                  switch (resolution) {
                    case HOUR -> Duration.ofHours(1);
                    case DAY -> Duration.ofDays(1);
                    case WEEK -> Duration.ofDays(7);
                    default -> throw new IllegalStateException("Unexpected test resolution");
                  });
      String bucket =
          switch (resolution) {
            case HOUR -> "1h";
            case DAY -> "1d";
            case WEEK -> "1w";
            default -> throw new IllegalStateException("Unexpected test resolution");
          };
      var trend = body(browser.get(query(bucket, start, end) + "&sensorId=" + SENSOR), 200);
      assertEquals(bucket, trend.path("bucket").asText());
      assertEquals(start, Instant.parse(trend.path("from").asText()));
      assertEquals(end, Instant.parse(trend.path("to").asText()));
      assertEquals(1, trend.path("series").get(0).path("buckets").size());
    }
    assertProblem(browser.get(base), 400, "INVALID_SENSORS");
    assertProblem(browser.get(base + ("&sensorId=" + SENSOR).repeat(2)), 400, "INVALID_SENSORS");
    assertProblem(browser.get(base + ("&sensorId=" + SENSOR).repeat(11)), 400, "INVALID_SENSORS");
    assertProblem(browser.get(base + "&sensorId=invalid"), 400, "INVALID_SENSORS");
    assertProblem(browser.get(base.replace("15m", "3m")), 400, "INVALID_BUCKET");
    assertProblem(browser.get(base.replace(from.toString(), "bad")), 400, "INVALID_RANGE");
    assertProblem(browser.get(query("15m", from.plusSeconds(1), to)), 400, "INVALID_RANGE");
    assertProblem(
        browser.get(query("15m", from.minus(Duration.ofDays(31)), to)), 400, "INVALID_RANGE");
    assertProblem(
        browser.get(query("15m", from, to.plus(QUARTER.multipliedBy(2)))), 400, "INVALID_RANGE");
    assertProblem(
        browser.get(base + "&sensorId=" + PRIVATE_SENSOR + "&sensorId=" + SENSOR),
        404,
        "RESOURCE_NOT_FOUND");
  }

  private static void verifySwitchAndRevoke(
      Browser a, Browser b, Connection owner, String cursor, Instant now) throws Exception {
    String csrf = a.csrf();
    String login = credentials("operator-b", TEST_PASSWORD);
    assertEquals(
        B.toString(),
        body(b.send("POST", "/session", login, b.csrf()), 200).path("activeTenantId").asText());
    assertEquals(31, historicalMean(b, now));
    assertProblem(b.get("/sensors?cursor=" + cursor), 400, "INVALID_CURSOR");

    String selectB = JSON.writeValueAsString(new BrowserSession.TenantChoice(B));
    assertProblem(a.send("PUT", "/session/tenant", "{}", csrf), 400, "INVALID_TENANT");
    assertProblem(a.send("PUT", "/session/tenant", selectB, csrf), 403, "FORBIDDEN");
    try (var sql = owner.createStatement()) {
      assertEquals(
          1,
          sql.executeUpdate(
              """
        INSERT INTO registry.operator_memberships (tenant_id, operator_id, role)
        SELECT '%s', operator_id, 'viewer' FROM registry.operators WHERE username='operator-a'
        """
                  .formatted(B)));
    }
    assertEquals(2, body(a.get("/session"), 200).path("tenants").size());
    String before = a.cookie();
    var switched = body(a.send("PUT", "/session/tenant", selectB, csrf), 200);
    assertEquals(B.toString(), switched.path("activeTenantId").asText());
    assertNotEquals(before, a.cookie());
    assertEquals(31, historicalMean(a, now));
    assertProblem(a.get("/sensors?cursor=" + cursor), 400, "INVALID_CURSOR");
    assertProblem(a.send("DELETE", "/session", null, csrf), 403, "INVALID_CSRF");
    try (var sql = owner.createStatement()) {
      assertEquals(
          1,
          sql.executeUpdate(
              """
        DELETE FROM registry.operator_memberships m USING registry.operators o
        WHERE m.operator_id=o.operator_id AND o.username='operator-a' AND m.tenant_id='%s'
        """
                  .formatted(B)));
    }
    assertProblem(a.get("/sensors"), 403, "FORBIDDEN");
    assertProblem(a.get("/session"), 403, "FORBIDDEN");
    var logout = a.send("DELETE", "/session", null, a.csrf());
    assertEquals(204, logout.statusCode());
    assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
    assertProblem(a.get("/sensors"), 401, "AUTHENTICATION_REQUIRED");
  }

  private static void verifyCommands(Browser a, Browser b, int port, Connection owner)
      throws Exception {
    var browserPath = "/sensors/" + SENSOR + "/commands";
    var csrf = a.csrf();
    assertProblem(
        a.send("POST", browserPath, "{\"enabled\":false}", null, "missing-csrf"),
        403,
        "INVALID_CSRF");
    assertProblem(a.send("POST", browserPath, "{}", csrf, "missing-state"), 400, "INVALID_COMMAND");
    assertProblem(
        a.send("POST", browserPath, "{\"enabled\":false}", csrf, "wrong-scope", B.toString()),
        409,
        "SESSION_CHANGED");
    var accepted = a.send("POST", browserPath, "{\"enabled\":false}", csrf, "pause");
    var pause = body(accepted, 202);
    assertEquals("pending", pause.path("status").asText());
    String id = pause.path("commandId").asText();
    String statusPath = "/commands/" + id;
    assertEquals("/api/v1" + statusPath, accepted.headers().firstValue("Location").orElseThrow());
    assertEquals(
        pause, body(a.send("POST", browserPath, "{\"enabled\":false}", csrf, "pause"), 202));
    assertProblem(
        a.send("POST", browserPath, "{\"enabled\":true}", csrf, "pause"),
        409,
        "IDEMPOTENCY_CONFLICT");
    body(b.send("POST", "/session", credentials("operator-b", TEST_PASSWORD), b.csrf()), 200);
    assertProblem(
        b.send("POST", browserPath, "{\"enabled\":true}", b.csrf(), "pause"), 403, "FORBIDDEN");
    assertProblem(b.get(statusPath), 404, "RESOURCE_NOT_FOUND");

    String service = "http://127.0.0.1:" + port + CommandProtocol.PATH;
    try (var gateway = HttpClient.newHttpClient()) {
      var foreign =
          gateway.send(
              internal(service + "/pending", 'b').GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, foreign.statusCode());
      assertEquals(0, PendingCommands.parseFrom(foreign.body()).getCommandsCount());
      var page =
          gateway.send(
              internal(service + "/pending", 'a').GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, page.statusCode());
      var wire = PendingCommands.parseFrom(page.body()).getCommands(0).getCommand();
      assertEquals(id, wire.getCommandId());
      assertTrue(wire.hasEnabled());
      assertFalse(wire.getEnabled());

      var ack = ack(pause, false);
      assertEquals(404, sendAck(gateway, service, 'b', ack));
      assertEquals(409, sendAck(gateway, service, 'a', ack.toBuilder().setVersion(99).build()));
      assertEquals(204, sendAck(gateway, service, 'a', ack));
      assertEquals(204, sendAck(gateway, service, 'a', ack));
      assertEquals("applied", body(a.get(statusPath), 200).path("status").asText());
      assertEquals(
          pause, body(a.send("POST", browserPath, "{\"enabled\":false}", csrf, "pause"), 202));

      var late = body(a.send("POST", browserPath, "{\"enabled\":true}", csrf, "late"), 202);
      var lateId = late.path("commandId").asText();
      Instant applied = Instant.now().minusSeconds(90).truncatedTo(ChronoUnit.MILLIS);
      try (var update =
          owner.prepareStatement(
              "UPDATE registry.commands SET created_at=?, expires_at=? WHERE command_id=?")) {
        update.setObject(1, applied.minusSeconds(2).atOffset(ZoneOffset.UTC));
        update.setObject(2, applied.plusSeconds(58).atOffset(ZoneOffset.UTC));
        update.setObject(3, UUID.fromString(lateId));
        assertEquals(1, update.executeUpdate());
      }
      var latePath = "/commands/" + lateId;
      assertEquals("expired", body(a.get(latePath), 200).path("status").asText());
      var lateAck = ack(late, true).toBuilder().setAppliedAtMs(applied.toEpochMilli()).build();
      assertEquals(204, sendAck(gateway, service, 'a', lateAck));
      var expired = body(a.get(latePath), 200);
      assertEquals("expired", expired.path("status").asText());
      assertEquals("COMMAND_EXPIRED", expired.path("reasonCode").asText());
      assertTrue(expired.path("confirmedEnabled").asBoolean());
      assertTrue(
          body(a.get("/sensors?limit=1"), 200)
              .path("items")
              .get(0)
              .path("confirmedEnabled")
              .asBoolean());
      try (var audit =
          owner.prepareStatement(
              "SELECT ack_received_at IS NOT NULL FROM registry.commands WHERE command_id=?")) {
        audit.setObject(1, UUID.fromString(lateId));
        try (var row = audit.executeQuery()) {
          assertTrue(row.next());
          assertTrue(row.getBoolean(1));
        }
      }

      var stale = body(a.send("POST", browserPath, "{\"enabled\":false}", csrf, "stale"), 202);
      var latest = body(a.send("POST", browserPath, "{\"enabled\":true}", csrf, "latest"), 202);
      assertEquals(stale.path("version").asLong() + 1, latest.path("version").asLong());
      var stalePath = "/commands/" + stale.path("commandId").asText();
      assertEquals("superseded", body(a.get(stalePath), 200).path("status").asText());
      assertEquals(204, sendAck(gateway, service, 'a', ack(latest, true)));
      assertEquals(204, sendAck(gateway, service, 'a', ack(stale, false)));
      assertEquals("superseded", body(a.get(stalePath), 200).path("status").asText());
      assertTrue(
          body(a.get("/sensors?limit=1"), 200)
              .path("items")
              .get(0)
              .path("confirmedEnabled")
              .asBoolean());
    }
  }

  private static HttpRequest.Builder internal(String url, char tenant) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + Character.toString(tenant).repeat(48))
        .header("Accept", CommandProtocol.CONTENT_TYPE);
  }

  private static int sendAck(
      HttpClient client, String service, char tenant, SamplingAcknowledgment ack) throws Exception {
    return client
        .send(
            internal(service + "/" + ack.getCommandId() + "/ack", tenant)
                .header("Content-Type", CommandProtocol.CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(ack.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  private static SamplingAcknowledgment ack(JsonNode command, boolean enabled) {
    return SamplingAcknowledgment.newBuilder()
        .setSchemaVersion(CommandProtocol.VERSION)
        .setCommandId(command.path("commandId").asText())
        .setSensorId(command.path("sensorId").asText())
        .setVersion(command.path("version").asLong())
        .setEnabled(enabled)
        .setAppliedAtMs(Instant.parse(command.path("createdAt").asText()).toEpochMilli())
        .build();
  }

  private static void verifyLiveness(Browser a, Browser b, int port, Connection owner)
      throws Exception {
    String device = "00000000-0000-4000-8000-000000000002";
    long now = System.currentTimeMillis();
    var live =
        SensorHeartbeatObservation.newBuilder()
            .setDeviceId(device)
            .setHeartbeat(
                TelemetryHeartbeat.newBuilder()
                    .setSchemaVersion(1)
                    .setSensorId(SENSOR.toString())
                    .setEnabled(true)
                    .setObservedAtMs(now)
                    .setAppliedCommandVersion(4))
            .build();
    try (var gateway = HttpClient.newHttpClient()) {
      String endpoint = "http://127.0.0.1:" + port + HeartbeatBatchLimits.PATH;
      var invalid = live.toBuilder().setDeviceId(new UUID(0, 999).toString()).build();
      assertEquals(204, postHeartbeat(gateway, endpoint, 'b', invalid));
      assertEquals("unknown", firstSensor(b).path("connectivity").asText());
      assertEquals(401, postHeartbeat(gateway, endpoint, 'c', live));
      assertEquals(204, postHeartbeat(gateway, endpoint, 'a', live));
      var sensor = firstSensor(a);
      assertEquals("online", sensor.path("connectivity").asText());
      assertEquals("stale", sensor.path("sampleStatus").asText());
      assertEquals("applied", sensor.path("latestCommand").path("status").asText());
      assertEquals(4, sensor.path("latestCommand").path("version").asLong());
      assertEquals("unknown", firstSensor(b).path("connectivity").asText());

      var replay =
          live.toBuilder()
              .setHeartbeat(live.getHeartbeat().toBuilder().setObservedAtMs(now - 5_000))
              .build();
      assertEquals(204, postHeartbeat(gateway, endpoint, 'a', replay));
      assertEquals(sensor.path("lastHeartbeatAt"), firstSensor(a).path("lastHeartbeatAt"));
      var future =
          live.toBuilder()
              .setHeartbeat(live.getHeartbeat().toBuilder().setObservedAtMs(now + 30_000))
              .build();
      assertEquals(204, postHeartbeat(gateway, endpoint, 'a', future));
      var clamped = firstSensor(a).path("lastHeartbeatAt");
      assertTrue(Instant.parse(clamped.asText()).isBefore(Instant.ofEpochMilli(now + 30_000)));
      assertEquals(204, postHeartbeat(gateway, endpoint, 'a', future));
      assertEquals(clamped, firstSensor(a).path("lastHeartbeatAt"));
      try (var update =
          owner.prepareStatement(
              """
          UPDATE registry.sensor_state SET last_heartbeat_at=clock_timestamp()-interval '16 seconds'
          WHERE tenant_id=? AND sensor_id=?
          """)) {
        update.setObject(1, A);
        update.setObject(2, SENSOR);
        assertEquals(1, update.executeUpdate());
      }
      assertEquals("offline", firstSensor(a).path("connectivity").asText());
      assertEquals("stale", firstSensor(a).path("sampleStatus").asText());
    }
  }

  private static int postHeartbeat(
      HttpClient http, String endpoint, char tenant, SensorHeartbeatObservation observation)
      throws Exception {
    var body = SensorHeartbeatBatch.newBuilder().addObservations(observation).build().toByteArray();
    return http.send(
            internal(endpoint, tenant)
                .header("Content-Type", CommandProtocol.CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  private static JsonNode firstSensor(Browser browser) throws Exception {
    return body(browser.get("/sensors?limit=1"), 200).path("items").get(0);
  }

  private static double historicalMean(Browser browser, Instant now) throws Exception {
    Instant end = Resolution.QUARTER_HOUR.endOf(now).minus(QUARTER);
    var bucket =
        body(browser.get(query("15m", end.minus(QUARTER), end) + "&sensorId=" + SENSOR), 200)
            .path("series")
            .get(0)
            .path("buckets")
            .get(0);
    assertEquals(1, bucket.path("count").asLong());
    assertFalse(bucket.path("partial").asBoolean());
    return bucket.path("mean").asDouble();
  }

  private static String query(String bucket, Instant from, Instant to) {
    return "/trends?bucket=" + bucket + "&from=" + from + "&to=" + to;
  }

  private static String credentials(String user, String password) {
    return JSON.writeValueAsString(new BrowserSession.Credentials(user, password, null));
  }

  private static JsonNode body(HttpResponse<String> response, int status) {
    assertEquals(status, response.statusCode(), response.body());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    return JSON.readTree(response.body());
  }

  private static void assertProblem(HttpResponse<String> response, int status, String code) {
    var problem = body(response, status);
    assertEquals(code, problem.path("code").asText());
    assertNotNull(UUID.fromString(problem.path("requestId").asText()));
  }

  private static final class Browser implements AutoCloseable {
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    private final String base;

    Browser(int port) {
      base = "http://127.0.0.1:" + port + "/api/v1";
    }

    String cookie() {
      return cookies.getCookieStore().getCookies().getFirst().getValue();
    }

    String csrf() throws Exception {
      var value = body(get("/session/csrf"), 200);
      assertEquals("X-CSRF-TOKEN", value.path("headerName").asText());
      return value.path("token").asText();
    }

    HttpResponse<String> get(String path) throws Exception {
      return send("GET", path, null, null);
    }

    HttpResponse<String> send(String method, String path, String content, String csrf)
        throws Exception {
      return send(method, path, content, csrf, null);
    }

    HttpResponse<String> send(String method, String path, String content, String csrf, String key)
        throws Exception {
      return send(method, path, content, csrf, key, null);
    }

    HttpResponse<String> send(
        String method, String path, String content, String csrf, String key, String tenant)
        throws Exception {
      var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
      if (tenant != null) {
        request.header("X-IoT-Tenant", tenant);
      }
      if (key != null) {
        request.header("Idempotency-Key", key);
      }
      if (csrf != null) {
        request.header("X-CSRF-TOKEN", csrf);
      }
      if (content != null) {
        request.header("Content-Type", "application/json");
      }
      return client.send(
          request
              .method(
                  method,
                  content == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(content))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
      client.close();
    }
  }
}
