package iot.platform.backend.analytics;

import static iot.platform.backend.analytics.TrendStorage.Resolution.*;
import static org.junit.jupiter.api.Assertions.*;

import iot.platform.backend.BackendApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class AnalyticsIntegrationTest {
  private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID SENSOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID CALENDAR_SENSOR =
      UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID PRIVATE_SENSOR = new UUID(0, 200);
  private static final Duration QUARTER = Duration.ofMinutes(15);
  @TempDir java.nio.file.Path directory;

  @Test
  void mergesMaterializedAndRawDataAndRepairsHistoryUsingThePinnedTimescaleImage()
      throws Exception {
    var image =
        DockerImageName.parse("timescale/timescaledb:2.27.0-pg17")
            .asCompatibleSubstituteFor("postgres");
    try (var database = new PostgreSQLContainer(image)) {
      database.start();
      String url = database.getJdbcUrl();
      try (var owner =
          DriverManager.getConnection(url, database.getUsername(), database.getPassword())) {
        initialize(owner);
        provision(url, "2");
        var near = Instant.now().truncatedTo(ChronoUnit.HOURS);
        var historical = near.minus(Duration.ofDays(5));
        insert(owner, A, SENSOR, 1, historical, 12);
        provision(url, "8");
        disableJobs(owner);
        provision(url, "9");

        try (var migration =
                DriverManager.getConnection(url, "iot_migrate", "test-migrate-fixture");
            var runtime =
                new SpringApplicationBuilder(BackendApplication.class)
                    .profiles("registry")
                    .run(
                        "--server.port=0",
                        "--IOT_REGISTRY_JDBC_URL=" + url,
                        "--IOT_REGISTRY_APP_PASSWORD=test-app-fixture",
                        "--spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'America/New_York'")) {
          var trends = runtime.getBean(TrendStorage.class);
          verifyUpgradedHistory(trends, owner, migration, historical);
          verifyBoundariesAndTenantScope(trends, owner, migration, near, url);
          verifyCalendarRollups(trends, owner, migration, near);
          verifyRepair(trends, owner, migration, historical);
          verifyScheduledRefresh(owner);
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

  private void provision(String url, String target) {
    try (var context =
        new SpringApplicationBuilder(BackendApplication.class)
            .web(WebApplicationType.NONE)
            .profiles("registry-provision")
            .run(
                "--spring.flyway.target=" + target,
                "--IOT_REGISTRY_JDBC_URL=" + url,
                "--IOT_REGISTRY_MIGRATE_PASSWORD=test-migrate-fixture",
                "--IOT_REGISTRY_GATEWAY_A_TOKEN=" + "a".repeat(48),
                "--IOT_REGISTRY_GATEWAY_B_TOKEN=" + "b".repeat(48),
                "--IOT_REGISTRY_ACL=" + directory.resolve("registry.acl"))) {
      assertTrue(context.isActive());
    }
  }

  private static void disableJobs(Connection owner) throws SQLException {
    try (var sql = owner.createStatement()) {
      try (var jobs =
          sql.executeQuery(
              """
          SELECT job_id, owner, schedule_interval=interval '1 minute' AS one_minute
          FROM timescaledb_information.jobs WHERE proc_schema='telemetry' ORDER BY proc_name
          """)) {
        for (int index = 0; index < 2; index++) {
          assertTrue(jobs.next());
          assertEquals("iot_migrate", jobs.getString("owner"));
          assertTrue(jobs.getBoolean("one_minute"));
        }
        assertFalse(jobs.next());
      }
      sql.execute(
          """
          SELECT public.alter_job(job_id, scheduled => false)
          FROM timescaledb_information.jobs WHERE proc_schema='telemetry'
          """);
      sql.execute(
          """
          UPDATE telemetry.analytics_coverage
          SET covered_from=NULL, covered_until=NULL, recent_refreshed_at=NULL
          """);
    }
  }

  private static void verifyUpgradedHistory(
      TrendStorage trends, Connection owner, Connection migration, Instant old)
      throws SQLException {
    var early =
        trends
            .read(A, List.of(SENSOR), old, old.plus(QUARTER), QUARTER_HOUR, Instant.now())
            .getFirst();
    assertEquals(1, early.count());
    assertEquals(12, early.mean());
    assertNull(early.materializedThrough());

    for (int pass = 0; pass < 5; pass++) {
      runJob(migration, "refresh_recent_analytics");
    }
    var covered =
        trends
            .read(A, List.of(SENSOR), old, old.plus(QUARTER), QUARTER_HOUR, Instant.now())
            .getFirst();
    assertEquals(early.mean(), covered.mean());
    assertNotNull(covered.materializedThrough());
    assertNotNull(covered.recentRefreshedAt());
    assertEquals(1, count(owner, "SELECT count(*) FROM telemetry.sensor_15m"));
  }

  private static void verifyBoundariesAndTenantScope(
      TrendStorage trends, Connection owner, Connection migration, Instant near, String url)
      throws SQLException {
    var previousHour = near.minus(Duration.ofHours(1));
    insert(owner, A, SENSOR, 10, previousHour.plusSeconds(1), 1);
    insert(owner, A, SENSOR, 11, previousHour.plusSeconds(2), 3);
    insert(owner, A, SENSOR, 12, previousHour.plusSeconds(3), 5);
    insert(owner, A, SENSOR, 13, previousHour.plus(QUARTER), 9);
    insert(owner, B, SENSOR, 10, previousHour.plusSeconds(1), 80);
    runJob(migration, "refresh_recent_analytics");

    var hour = trends.read(A, List.of(SENSOR), previousHour, near, HOUR, Instant.now()).getFirst();
    assertEquals(4, hour.count());
    assertEquals(1, hour.min());
    assertEquals(9, hour.max());
    assertEquals(4.5, hour.mean());
    assertEquals(previousHour.plus(QUARTER), hour.lastObservedAt());
    assertFalse(hour.partial());
    assertEquals(
        80,
        trends.read(B, List.of(SENSOR), previousHour, near, HOUR, Instant.now()).getFirst().mean());

    var edge = hour.materializedThrough();
    var end = edge.plus(QUARTER);
    insert(owner, A, SENSOR, 14, edge, 19);
    insert(owner, A, SENSOR, 15, end, 30);
    var quarters =
        trends.read(
            A,
            List.of(SENSOR),
            edge.minus(QUARTER.multipliedBy(2)),
            end,
            QUARTER_HOUR,
            edge.plusSeconds(2));
    var current = quarters.getLast();
    assertEquals(edge, current.start());
    assertEquals(end, current.end());
    assertEquals(1, current.count());
    assertEquals(19, current.mean());
    assertTrue(current.partial());
    var gap = quarters.get(1);
    assertEquals(0, gap.count());
    assertNull(gap.min());
    assertNull(gap.max());
    assertNull(gap.mean());
    assertNull(gap.lastObservedAt());
    assertFalse(gap.partial());

    try (var add =
        owner.prepareStatement(
            """
        INSERT INTO registry.sensors (tenant_id, sensor_id, device_id, type_code, display_name)
        SELECT tenant_id, ?, device_id, 'temperature', 'Tenant fixture'
        FROM registry.devices WHERE tenant_id=?
        """)) {
      add.setObject(1, PRIVATE_SENSOR);
      add.setObject(2, B);
      assertEquals(1, add.executeUpdate());
    }
    assertTrue(trends.read(A, List.of(PRIVATE_SENSOR), edge, end, QUARTER_HOUR, edge).isEmpty());
    assertEquals(1, trends.read(B, List.of(PRIVATE_SENSOR), edge, end, QUARTER_HOUR, edge).size());
    try (var app = DriverManager.getConnection(url, "iot_app", "test-app-fixture")) {
      assertEquals(
          0,
          count(
              app,
              """
          SELECT count(*) FROM telemetry.read_trend_buckets(
            ARRAY['00000000-0000-4000-8000-000000000001'::uuid],
            '2026-11-10T00:00Z', '2026-11-10T01:00Z', interval '1 hour', '2026-11-10T00:00Z')
          """));
      assertEquals(
          "42501",
          assertThrows(
                  SQLException.class, () -> count(app, "SELECT count(*) FROM telemetry.sensor_15m"))
              .getSQLState());
      assertEquals(
          "42501",
          assertThrows(
                  SQLException.class, () -> count(app, "SELECT count(*) FROM telemetry.readings"))
              .getSQLState());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> trends.read(A, List.of(SENSOR, SENSOR), edge, end, QUARTER_HOUR, edge));
    assertThrows(
        IllegalArgumentException.class,
        () -> trends.read(A, List.of(SENSOR), edge.plusSeconds(1), end, QUARTER_HOUR, edge));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            trends.read(
                A, List.of(SENSOR), edge.minus(Duration.ofDays(31)), end, QUARTER_HOUR, edge));
  }

  private static void verifyCalendarRollups(
      TrendStorage trends, Connection owner, Connection migration, Instant near)
      throws SQLException {
    var monday =
        near.atOffset(ZoneOffset.UTC)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);
    var sunday = monday.minus(Duration.ofDays(1));
    insert(owner, A, CALENDAR_SENSOR, 21, monday.minusSeconds(1), 36);
    insert(owner, A, CALENDAR_SENSOR, 22, monday, 61);
    runJob(migration, "refresh_recent_analytics");
    runJob(migration, "repair_analytics");

    var weekly =
        trends.read(
            A,
            List.of(CALENDAR_SENSOR),
            monday.minus(Duration.ofDays(7)),
            monday.plus(Duration.ofDays(7)),
            WEEK,
            monday.plusSeconds(1));
    assertEquals(2, weekly.size());
    assertEquals(monday, weekly.getFirst().end());
    assertEquals(36, weekly.getFirst().mean());
    assertEquals(monday, weekly.getLast().start());
    assertEquals(61, weekly.getLast().mean());
    assertTrue(weekly.getLast().partial());
    var daily =
        trends.read(
            A,
            List.of(CALENDAR_SENSOR),
            sunday,
            monday.plus(Duration.ofDays(1)),
            DAY,
            monday.plusSeconds(1));
    assertEquals(List.of(36.0, 61.0), daily.stream().map(TrendStorage.Bucket::mean).toList());
  }

  private static void verifyScheduledRefresh(Connection owner) throws Exception {
    try (var sql = owner.createStatement()) {
      sql.execute("UPDATE telemetry.analytics_coverage SET recent_refreshed_at=NULL");
      sql.execute(
          """
          SELECT public.alter_job(job_id, scheduled => true, next_start => clock_timestamp())
          FROM timescaledb_information.jobs
          WHERE proc_schema='telemetry' AND proc_name='refresh_recent_analytics'
          """);
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      if (count(
              owner,
              """
          SELECT count(*) FROM telemetry.analytics_coverage WHERE recent_refreshed_at IS NOT NULL
          """)
          == 1) {
        return;
      }
      Thread.sleep(100);
    }
    fail("TimescaleDB did not run the scheduled analytics refresh");
  }

  private static void verifyRepair(
      TrendStorage trends, Connection owner, Connection migration, Instant bucket)
      throws SQLException {
    owner.setAutoCommit(false);
    try {
      insert(owner, A, SENSOR, 32, bucket.plusSeconds(3), 70);
      assertEquals(1, count(owner, "SELECT count(*) FROM telemetry.analytics_repairs"));
    } finally {
      owner.rollback();
      owner.setAutoCommit(true);
    }
    assertEquals(0, count(owner, "SELECT count(*) FROM telemetry.analytics_repairs"));
    insert(owner, A, SENSOR, 30, bucket.plusSeconds(4), 18);
    long revision = count(owner, "SELECT max(revision) FROM telemetry.analytics_repairs");
    insert(owner, A, SENSOR, 31, bucket.plusSeconds(5), 30);
    assertEquals(1, count(owner, "SELECT count(*) FROM telemetry.analytics_repairs"));
    assertTrue(count(owner, "SELECT max(revision) FROM telemetry.analytics_repairs") > revision);
    var waiting =
        trends
            .read(A, List.of(SENSOR), bucket, bucket.plus(QUARTER), QUARTER_HOUR, Instant.now())
            .getFirst();
    assertEquals(1, waiting.count());
    assertTrue(waiting.repairPending());
    assertFalse(
        trends
            .read(B, List.of(SENSOR), bucket, bucket.plus(QUARTER), QUARTER_HOUR, Instant.now())
            .getFirst()
            .repairPending());

    runJob(migration, "repair_analytics");
    var repaired =
        trends
            .read(A, List.of(SENSOR), bucket, bucket.plus(QUARTER), QUARTER_HOUR, Instant.now())
            .getFirst();
    assertFalse(repaired.repairPending());
    assertEquals(3, repaired.count());
    assertEquals(20, repaired.mean());
    assertEquals(bucket.plusSeconds(5), repaired.lastObservedAt());
    assertEquals(0, count(owner, "SELECT count(*) FROM telemetry.analytics_repairs"));
    insert(owner, A, SENSOR, 33, bucket.minus(Duration.ofDays(31)), 17);
    assertEquals(1, count(owner, "SELECT count(*) FROM telemetry.analytics_repairs"));
    runJob(migration, "repair_analytics");
    assertEquals(0, count(owner, "SELECT count(*) FROM telemetry.analytics_repairs"));
  }

  private static void insert(
      Connection db, UUID tenant, UUID sensor, long id, Instant at, double value)
      throws SQLException {
    UUID event = new UUID(0, id);
    try (var sql =
        db.prepareStatement(
            """
        INSERT INTO telemetry.event_receipts(tenant_id,sensor_id,event_id,fingerprint)
        VALUES (?, ?, ?, decode(repeat('00',32),'hex'))
        """)) {
      sql.setObject(1, tenant);
      sql.setObject(2, sensor);
      sql.setObject(3, event);
      sql.executeUpdate();
    }
    try (var sql =
        db.prepareStatement(
            """
        INSERT INTO telemetry.readings(tenant_id,sensor_id,event_id,observed_at,gateway_received_at,
          backend_received_at,schema_version,value,payload_bytes)
        VALUES (?, ?, ?, ?, clock_timestamp(), clock_timestamp(), 1, ?, 94)
        """)) {
      sql.setObject(1, tenant);
      sql.setObject(2, sensor);
      sql.setObject(3, event);
      sql.setObject(4, at.atOffset(ZoneOffset.UTC));
      sql.setDouble(5, value);
      sql.executeUpdate();
    }
  }

  private static void runJob(Connection db, String procedure) throws SQLException {
    int id;
    try (var lookup =
        db.prepareStatement(
            """
        SELECT job_id FROM timescaledb_information.jobs WHERE proc_schema='telemetry' AND proc_name=?
        """)) {
      lookup.setString(1, procedure);
      try (var rows = lookup.executeQuery()) {
        assertTrue(rows.next());
        id = rows.getInt(1);
      }
    }
    try (var call = db.prepareStatement("CALL public.run_job(?)")) {
      call.setInt(1, id);
      call.execute();
    }
  }

  private static long count(Connection db, String query) throws SQLException {
    try (var statement = db.createStatement();
        var rows = statement.executeQuery(query)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }
}
