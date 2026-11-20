package iot.platform.backend.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@Profile("registry")
class PlatformHealth {
  private final JdbcTemplate jdbc;
  private final AtomicReference<Map<String, Object>> snapshot = new AtomicReference<>(Map.of());

  PlatformHealth(JdbcTemplate jdbc, MeterRegistry meters) {
    this.jdbc = jdbc;
    Gauge.builder("iot.database.available", snapshot, data -> data.get().isEmpty() ? 0 : 1)
        .register(meters);
    register(meters, "iot.sensor.enabled", "enabled_sensors");
    register(meters, "iot.sensor.stale", "stale_sensors");
    register(meters, "iot.sensor.offline", "offline_sensors");
    register(meters, "iot.command.pending", "ready_commands");
    register(meters, "iot.command.oldest.pending.seconds", "oldest_command_seconds");
    register(meters, "iot.aggregate.last.refresh.seconds", "aggregate_refresh_seconds");
    register(meters, "iot.wal.archive.enabled", "wal_archiving");
    register(meters, "iot.wal.last.archive.seconds", "last_wal_archive_seconds");
    register(meters, "iot.wal.archive.failures", "wal_archive_failures");
    for (String outcome : new String[] {"applied", "expired", "failed", "superseded"}) {
      Gauge.builder("iot.command.outcomes", snapshot, data -> value(data, outcome + "_commands"))
          .tag("outcome", outcome)
          .register(meters);
    }
  }

  @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
  void refresh() {
    try {
      snapshot.set(jdbc.queryForMap("SELECT * FROM telemetry.platform_health()"));
    } catch (DataAccessException unavailable) {
      snapshot.set(Map.of());
    }
  }

  private void register(MeterRegistry meters, String metric, String column) {
    Gauge.builder(metric, snapshot, data -> value(data, column)).register(meters);
  }

  private static double value(AtomicReference<Map<String, Object>> data, String column) {
    var number = (Number) data.get().get(column);
    return number == null ? Double.NaN : number.doubleValue();
  }
}
