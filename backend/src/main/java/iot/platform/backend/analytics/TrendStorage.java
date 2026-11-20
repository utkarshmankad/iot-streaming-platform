package iot.platform.backend.analytics;

import iot.platform.backend.registry.TenantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads trend buckets for an already authorized tenant. */
@Service
@Profile("registry")
public class TrendStorage {
  private static final Duration MAX_RANGE = Duration.ofDays(30);
  private static final long UTC_MONDAY_ORIGIN =
      Instant.parse("2000-01-03T00:00:00Z").getEpochSecond();

  private final JdbcClient jdbc;
  private final TenantScope scope;

  TrendStorage(JdbcClient jdbc, TenantScope scope) {
    this.jdbc = jdbc;
    this.scope = scope;
  }

  @Transactional(readOnly = true, timeout = 5)
  public List<Bucket> read(
      UUID authorizedTenant,
      List<UUID> sensors,
      Instant from,
      Instant to,
      Resolution resolution,
      Instant asOf) {
    Objects.requireNonNull(authorizedTenant);
    Objects.requireNonNull(asOf);
    var unique = new LinkedHashSet<>(sensors);
    if (sensors.isEmpty()
        || sensors.size() > 10
        || unique.size() != sensors.size()
        || unique.contains(null)) {
      throw new IllegalArgumentException("Specify one to ten distinct sensor IDs");
    }
    if (!from.isBefore(to)
        || Duration.between(from, to).compareTo(MAX_RANGE) > 0
        || !resolution.isAligned(from)
        || !resolution.isAligned(to)) {
      throw new IllegalArgumentException("Specify a UTC-aligned range of at most 30 days");
    }

    scope.bind(authorizedTenant);
    return jdbc.sql(
            """
        SELECT sensor_id, bucket_start, bucket_end, samples, minimum, maximum, mean, partial,
               last_observed_at, covered_until, recent_refreshed_at, repair_pending
        FROM telemetry.read_trend_buckets(CAST(? AS uuid[]), ?, ?, CAST(? AS interval), ?)
        ORDER BY sensor_id, bucket_start
        """)
        .param(new SqlArrayValue("uuid", (Object[]) sensors.toArray(UUID[]::new)))
        .param(from.atOffset(ZoneOffset.UTC))
        .param(to.atOffset(ZoneOffset.UTC))
        .param(resolution.interval)
        .param(asOf.atOffset(ZoneOffset.UTC))
        .query(
            (row, number) ->
                new Bucket(
                    row.getObject("sensor_id", UUID.class), instant(row, "bucket_start"),
                    instant(row, "bucket_end"), row.getLong("samples"),
                    row.getObject("minimum", Double.class), row.getObject("maximum", Double.class),
                    row.getObject("mean", Double.class), row.getBoolean("partial"),
                    instant(row, "last_observed_at"), instant(row, "covered_until"),
                    instant(row, "recent_refreshed_at"), row.getBoolean("repair_pending")))
        .list();
  }

  private static Instant instant(ResultSet row, String field) throws SQLException {
    var value = row.getObject(field, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public enum Resolution {
    QUARTER_HOUR("15 minutes", Duration.ofMinutes(15)),
    HOUR("1 hour", Duration.ofHours(1)),
    DAY("1 day", Duration.ofDays(1)),
    WEEK("1 week", Duration.ofDays(7));

    private final String interval;
    private final Duration duration;

    Resolution(String interval, Duration duration) {
      this.interval = interval;
      this.duration = duration;
    }

    public Instant endOf(Instant timestamp) {
      long seconds = duration.getSeconds();
      long lower =
          timestamp.getEpochSecond()
              - Math.floorMod(timestamp.getEpochSecond() - UTC_MONDAY_ORIGIN, seconds);
      return Instant.ofEpochSecond(lower + seconds);
    }

    public boolean isAligned(Instant timestamp) {
      return timestamp.getNano() == 0
          && Math.floorMod(timestamp.getEpochSecond() - UTC_MONDAY_ORIGIN, duration.getSeconds())
              == 0;
    }
  }

  public record Bucket(
      UUID sensorId,
      Instant start,
      Instant end,
      long count,
      Double min,
      Double max,
      Double mean,
      boolean partial,
      Instant lastObservedAt,
      Instant materializedThrough,
      Instant recentRefreshedAt,
      boolean repairPending) {}
}
