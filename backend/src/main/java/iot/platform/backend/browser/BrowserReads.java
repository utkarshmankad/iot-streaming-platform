package iot.platform.backend.browser;

import iot.platform.backend.analytics.TrendStorage;
import iot.platform.backend.registry.OperatorTenantAuthorizer;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("registry")
class BrowserReads {
  private final JdbcClient jdbc;
  private final OperatorTenantAuthorizer authorizer;
  private final TrendStorage trends;
  private final int queriesPerMinute;
  private final Map<UUID, QueryWindow> trendUsage = new ConcurrentHashMap<>();

  BrowserReads(
      JdbcClient jdbc,
      OperatorTenantAuthorizer authorizer,
      TrendStorage trends,
      @Value("${IOT_TRENDS_PER_TENANT_PER_MINUTE:120}") int queriesPerMinute) {
    this.jdbc = jdbc;
    this.authorizer = authorizer;
    this.trends = trends;
    this.queriesPerMinute = queriesPerMinute;
  }

  @PostConstruct
  void validateLimit() {
    if (queriesPerMinute < 1) {
      throw new IllegalArgumentException("The tenant query limit must be positive");
    }
  }

  @Transactional(readOnly = true, timeout = 5)
  public SensorPage sensors(
      BrowserSession.Principal principal, int limit, String cursor, String sessionId, Instant now) {
    authorize(principal);
    UUID tenant = principal.tenantId();
    UUID after = BrowserCursor.decode(cursor, tenant, sessionId);
    var found =
        jdbc.sql(
                """
        SELECT s.sensor_id, s.display_name, s.type_code, t.canonical_unit, s.sampling_period_ms,
               coalesce(st.desired_enabled, true) AS desired_enabled, st.confirmed_enabled,
               st.last_heartbeat_at, st.last_observed_at
        FROM registry.sensors s JOIN registry.sensor_types t USING (type_code)
        LEFT JOIN registry.sensor_state st USING (tenant_id, sensor_id)
        WHERE s.tenant_id=:tenant AND s.status='active'
          AND (CAST(:after AS uuid) IS NULL OR s.sensor_id > CAST(:after AS uuid))
        ORDER BY s.sensor_id LIMIT :limit
        """)
            .param("tenant", tenant)
            .param("after", after)
            .param("limit", limit + 1)
            .query(
                (row, index) ->
                    new RegisteredSensor(
                        row.getObject("sensor_id", UUID.class),
                        row.getString("display_name"),
                        row.getString("type_code"),
                        row.getString("canonical_unit"),
                        row.getInt("sampling_period_ms"),
                        row.getBoolean("desired_enabled"),
                        row.getObject("confirmed_enabled", Boolean.class),
                        instant(row, "last_observed_at"),
                        instant(row, "last_heartbeat_at")))
            .list();
    boolean more = found.size() > limit;
    var page = more ? found.subList(0, limit) : found;
    var latest = lastObservations(page);
    var entries =
        page.stream().map(sensor -> status(sensor, latest.get(sensor.sensorId()), now)).toList();
    String next = more ? BrowserCursor.encode(tenant, page.getLast().sensorId(), sessionId) : null;
    return new SensorPage(entries, next);
  }

  @Transactional(readOnly = true, timeout = 5)
  public TrendView trend(
      BrowserSession.Principal principal,
      List<UUID> requested,
      Instant from,
      Instant to,
      String bucket,
      TrendStorage.Resolution resolution,
      Instant now) {
    authorize(principal);
    UUID tenant = principal.tenantId();
    reserve(tenant, now);
    var sensors =
        jdbc.sql(
                """
        SELECT s.sensor_id, s.type_code, t.canonical_unit
        FROM registry.sensors s JOIN registry.sensor_types t USING (type_code)
        WHERE s.tenant_id=? AND s.status='active' AND s.sensor_id=ANY(CAST(? AS uuid[]))
        """)
            .param(tenant)
            .param(new SqlArrayValue("uuid", (Object[]) requested.toArray(UUID[]::new)))
            .query(
                (row, number) ->
                    new SeriesIdentity(
                        row.getObject("sensor_id", UUID.class),
                        row.getString("type_code"),
                        row.getString("canonical_unit")))
            .list();
    if (sensors.size() != requested.size()) {
      throw BrowserProblems.missing();
    }
    var bySensor =
        trends.read(tenant, requested, from, to, resolution, now).stream()
            .collect(Collectors.groupingBy(TrendStorage.Bucket::sensorId));
    Map<UUID, SeriesIdentity> metadata =
        sensors.stream().collect(Collectors.toMap(SeriesIdentity::sensorId, identity -> identity));
    var series =
        requested.stream()
            .map(id -> series(metadata.get(id), bySensor.getOrDefault(id, List.of()), now))
            .toList();
    return new TrendView(from, to, bucket, now, series);
  }

  private void authorize(BrowserSession.Principal principal) {
    if (authorizer.activeMembership(principal.operatorId(), principal.tenantId()).isEmpty()) {
      throw BrowserProblems.forbidden();
    }
  }

  private void reserve(UUID tenant, Instant now) {
    long minute = now.getEpochSecond() / 60;
    var current =
        trendUsage.compute(
            tenant,
            (id, window) ->
                window == null || window.minute() != minute
                    ? new QueryWindow(minute, 1)
                    : new QueryWindow(minute, Math.min(queriesPerMinute + 1L, window.used() + 1)));
    if (current.used() > queriesPerMinute) {
      throw new BrowserProblems.Failure(
          HttpStatus.TOO_MANY_REQUESTS,
          "TREND_RATE_LIMIT",
          "Try this tenant's trend query again in a minute.");
    }
  }

  private Map<UUID, Instant> lastObservations(List<RegisteredSensor> page) {
    var latest = new HashMap<UUID, Instant>();
    if (!page.isEmpty()) {
      var ids = page.stream().map(RegisteredSensor::sensorId).toArray(UUID[]::new);
      jdbc.sql(
              "SELECT sensor_id, observed_at FROM telemetry.browser_last_observations(CAST(? AS uuid[]))")
          .param(new SqlArrayValue("uuid", (Object[]) ids))
          .query(
              rows -> {
                while (rows.next()) {
                  latest.put(rows.getObject("sensor_id", UUID.class), instant(rows, "observed_at"));
                }
                return latest;
              });
    }
    return latest;
  }

  private static SensorView status(RegisteredSensor sensor, Instant fromReadings, Instant now) {
    Instant lastSeen = latest(sensor.lastObservedAt(), fromReadings);
    Instant heartbeat = sensor.lastHeartbeatAt();
    String connectivity =
        heartbeat == null
            ? "unknown"
            : heartbeat.isAfter(now.minusSeconds(15)) ? "online" : "offline";
    String samples;
    if (Boolean.FALSE.equals(sensor.confirmedEnabled())) {
      samples = "paused";
    } else if (lastSeen == null) {
      samples = "unknown";
    } else {
      samples = lastSeen.isAfter(now.minusSeconds(10)) ? "fresh" : "stale";
    }
    return new SensorView(
        sensor.sensorId(),
        sensor.displayName(),
        sensor.type(),
        sensor.unit(),
        sensor.period(),
        sensor.desiredEnabled(),
        sensor.confirmedEnabled(),
        connectivity,
        samples,
        lastSeen,
        heartbeat);
  }

  private static TrendSeries series(
      SeriesIdentity identity, List<TrendStorage.Bucket> rows, Instant now) {
    Instant lastSeen = null;
    Instant refreshed = null;
    Instant boundary = null;
    boolean pending = false;
    for (var row : rows) {
      lastSeen = latest(lastSeen, row.lastObservedAt());
      refreshed = latest(refreshed, row.recentRefreshedAt());
      boundary = latest(boundary, row.materializedThrough());
      pending |= row.repairPending();
    }
    String state =
        pending
            ? "repair_pending"
            : refreshed != null && refreshed.isBefore(now.minusSeconds(120))
                ? "stale"
                : lastSeen == null ? "unknown" : "fresh";
    var buckets =
        rows.stream()
            .map(
                row ->
                    new TrendBucket(
                        row.start(),
                        row.end(),
                        row.count(),
                        row.min(),
                        row.max(),
                        row.mean(),
                        row.partial()))
            .toList();
    return new TrendSeries(
        identity.sensorId(),
        identity.type(),
        identity.unit(),
        new Freshness(state, lastSeen, refreshed, boundary),
        buckets);
  }

  private static Instant latest(Instant a, Instant b) {
    return a == null || (b != null && b.isAfter(a)) ? b : a;
  }

  private static Instant instant(ResultSet row, String key) throws SQLException {
    var stamp = row.getObject(key, OffsetDateTime.class);
    return stamp == null ? null : stamp.toInstant();
  }

  private record QueryWindow(long minute, long used) {}

  private record RegisteredSensor(
      UUID sensorId,
      String displayName,
      String type,
      String unit,
      int period,
      boolean desiredEnabled,
      Boolean confirmedEnabled,
      Instant lastObservedAt,
      Instant lastHeartbeatAt) {}

  private record SeriesIdentity(UUID sensorId, String type, String unit) {}

  record SensorPage(List<SensorView> items, String nextCursor) {}

  record SensorView(
      UUID sensorId,
      String displayName,
      String type,
      String unit,
      int samplingPeriodMs,
      boolean desiredEnabled,
      Boolean confirmedEnabled,
      String connectivity,
      String sampleStatus,
      Instant lastObservedAt,
      Instant lastHeartbeatAt) {}

  record TrendView(
      Instant from, Instant to, String bucket, Instant generatedAt, List<TrendSeries> series) {}

  record TrendSeries(
      UUID sensorId, String type, String unit, Freshness freshness, List<TrendBucket> buckets) {}

  record Freshness(
      String state,
      Instant lastObservedAt,
      Instant recentRefreshedAt,
      Instant materializedThrough) {}

  record TrendBucket(
      Instant start,
      Instant end,
      long count,
      Double min,
      Double max,
      Double mean,
      boolean partial) {}
}
