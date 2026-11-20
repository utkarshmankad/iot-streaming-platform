package iot.platform.backend.registry;

import static org.junit.jupiter.api.Assertions.*;

import iot.platform.backend.BackendApplication;
import iot.platform.registry.proto.RegistrySnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class RegistryIntegrationTest {
  private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID OPERATOR_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-000000000001");
  private static final UUID OPERATOR_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-000000000001");
  private static final String TOKEN_A = "a".repeat(48);
  private static final String TOKEN_B = "b".repeat(48);

  @TempDir Path directory;

  @Test
  void migrationsProvisioningRlsAndRegistryEndpointUseOnlyAuthorizedTenants() throws Exception {
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
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), "iot_migrate", "test-migrate-fixture")
            .schemas("registry")
            .createSchemas(false)
            .target("1")
            .load()
            .migrate();
        sql.execute(
            """
            INSERT INTO registry.tenants (tenant_id, slug, display_name)
            VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-a', 'Existing demo tenant')
            """);
        Path acl = directory.resolve("registry.acl");
        provision(database.getJdbcUrl(), acl);
        provision(database.getJdbcUrl(), acl);
        try (var upgrade =
            sql.executeQuery(
                """
            SELECT display_name, to_regclass('telemetry.readings') IS NOT NULL AS migrated
            FROM registry.tenants WHERE slug='demo-a'
            """)) {
          assertTrue(upgrade.next());
          assertEquals("Existing demo tenant", upgrade.getString("display_name"));
          assertTrue(upgrade.getBoolean("migrated"));
        }
        try (var counts =
            sql.executeQuery("SELECT count(*), count(DISTINCT sensor_id) FROM registry.sensors")) {
          assertTrue(counts.next());
          assertEquals(12, counts.getInt(1));
          assertEquals(6, counts.getInt(2));
        }
        var assignments = Files.readAllLines(directory.resolve("simulator.tsv"));
        assertEquals(12, assignments.size());
        assertTrue(
            assignments.stream()
                .anyMatch(
                    row -> row.startsWith(TENANT_A + "\t") && row.endsWith("\toxygen\t1000")));
        assertTrue(
            assignments.stream()
                .anyMatch(row -> row.startsWith(TENANT_B + "\t") && row.endsWith("\tlight\t1000")));
        String text = Files.readString(acl);
        String aRules =
            text.substring(text.indexOf("user device-a"), text.indexOf("user device-b"));
        assertTrue(aRules.contains("topic write iot/v1/" + TENANT_A));
        assertTrue(aRules.contains("/receipt"));
        assertFalse(aRules.contains(TENANT_B.toString()));

        try (var runtime =
                new SpringApplicationBuilder(BackendApplication.class)
                    .profiles("registry")
                    .web(WebApplicationType.SERVLET)
                    .run(
                        "--server.port=0",
                        "--spring.datasource.hikari.maximum-pool-size=1",
                        "--IOT_REGISTRY_JDBC_URL=" + database.getJdbcUrl(),
                        "--IOT_REGISTRY_APP_PASSWORD=test-app-fixture");
            var http = HttpClient.newHttpClient()) {
          var endpoint =
              URI.create(
                  "http://127.0.0.1:"
                      + runtime.getEnvironment().getProperty("local.server.port")
                      + "/internal/v1/registry");
          assertEquals(401, request(http, endpoint, null, null).statusCode());
          assertEquals(401, request(http, endpoint, "not-a-token", null).statusCode());
          assertEquals(401, request(http, endpoint, "c".repeat(48), null).statusCode());
          var a = request(http, endpoint, TOKEN_A, null);
          assertEquals(200, a.statusCode());
          var aRegistry = RegistrySnapshot.parseFrom(a.body());
          var bRegistry = RegistrySnapshot.parseFrom(request(http, endpoint, TOKEN_B, null).body());
          assertEquals(TENANT_A.toString(), aRegistry.getTenantId());
          assertEquals(TENANT_B.toString(), bRegistry.getTenantId());
          assertEquals(aRegistry.getGatewayId(), bRegistry.getGatewayId());
          assertEquals(6, aRegistry.getDevices(0).getSensorsCount());
          assertEquals(
              aRegistry.getDevices(0).getSensors(0).getSensorId(),
              bRegistry.getDevices(0).getSensors(0).getSensorId());
          assertEquals(1, aRegistry.getDevices(0).getSensors(0).getAcceptedSchemas(0).getVersion());
          var etag = a.headers().firstValue("ETag").orElseThrow();
          assertEquals(304, request(http, endpoint, TOKEN_A, etag).statusCode());
          assertEquals(200, request(http, endpoint, TOKEN_B, etag).statusCode());

          var authorizer = runtime.getBean(OperatorTenantAuthorizer.class);
          assertEquals(Optional.of("operator"), authorizer.activeMembership(OPERATOR_A, TENANT_A));
          assertEquals(Optional.of("viewer"), authorizer.activeMembership(OPERATOR_B, TENANT_B));
          assertTrue(authorizer.activeMembership(OPERATOR_A, TENANT_B).isEmpty());
          var jdbc = runtime.getBean(JdbcClient.class);
          assertEquals(
              0, jdbc.sql("SELECT count(*) FROM registry.sensors").query(Integer.class).single());
          assertEquals(
              0, jdbc.sql("UPDATE registry.sensor_state SET desired_enabled=false").update());
          assertThrows(
              org.springframework.dao.DataAccessException.class,
              () -> jdbc.sql("UPDATE registry.sensor_state SET last_heartbeat_at=now()").update());

          sql.execute(
              "UPDATE registry.sensor_schema_versions SET accepted=false WHERE type_code='temperature'");
          try (var disable =
              owner.prepareStatement(
                  "UPDATE registry.sensors SET status='disabled' WHERE tenant_id=?")) {
            disable.setObject(1, TENANT_B);
            disable.executeUpdate();
          }
          var changed = request(http, endpoint, TOKEN_A, etag);
          assertEquals(200, changed.statusCode());
          assertEquals(
              0,
              RegistrySnapshot.parseFrom(changed.body())
                  .getDevices(0)
                  .getSensors(0)
                  .getAcceptedSchemasCount());
          assertEquals(
              0,
              RegistrySnapshot.parseFrom(request(http, endpoint, TOKEN_B, null).body())
                  .getDevices(0)
                  .getSensorsCount());
          try (var disable =
              owner.prepareStatement(
                  "UPDATE registry.gateways SET status='disabled' WHERE tenant_id=?")) {
            disable.setObject(1, TENANT_B);
            disable.executeUpdate();
          }
          assertEquals(401, request(http, endpoint, TOKEN_B, null).statusCode());
          provision(database.getJdbcUrl(), acl);
          assertFalse(Files.readString(acl).contains("user device-b"));
          assertEquals(401, request(http, endpoint, TOKEN_B, null).statusCode());
        }
      }
    }
  }

  private static void provision(String url, Path acl) {
    try (var context =
        new SpringApplicationBuilder(BackendApplication.class)
            .profiles("registry-provision")
            .web(WebApplicationType.NONE)
            .run(
                "--IOT_REGISTRY_JDBC_URL=" + url,
                "--IOT_REGISTRY_MIGRATE_PASSWORD=test-migrate-fixture",
                "--IOT_REGISTRY_GATEWAY_A_TOKEN=" + TOKEN_A,
                "--IOT_REGISTRY_GATEWAY_B_TOKEN=" + TOKEN_B,
                "--IOT_REGISTRY_ACL=" + acl)) {
      assertTrue(context.isActive());
    }
  }

  private static HttpResponse<byte[]> request(
      HttpClient client, URI endpoint, String token, String etag) throws Exception {
    var request = HttpRequest.newBuilder(endpoint).GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (etag != null) {
      request.header("If-None-Match", etag);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }
}
