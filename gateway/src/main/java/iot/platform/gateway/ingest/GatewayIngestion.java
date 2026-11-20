package iot.platform.gateway.ingest;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import iot.platform.gateway.registry.GatewayRegistries;
import iot.platform.telemetry.TelemetryBatchLimits;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("registry")
final class GatewayIngestion {
  private static final Logger LOG = LoggerFactory.getLogger(GatewayIngestion.class);
  private final GatewaySpool spool;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final List<Channel> channels = new ArrayList<>();
  private long nextMetrics;

  GatewayIngestion(
      GatewayRegistries registries,
      MeterRegistry meters,
      @Value("${IOT_REGISTRY_URL}") URI registryEndpoint,
      @Value("${IOT_GATEWAY_MQTT_URI:tcp://mosquitto:1883}") String mqttEndpoint,
      @Value("${IOT_GATEWAY_MQTT_A_PASSWORD:}") String passwordA,
      @Value("${IOT_GATEWAY_MQTT_B_PASSWORD:}") String passwordB,
      @Value("${IOT_GATEWAY_SPOOL:/var/lib/iot/gateway.sqlite}") Path database,
      @Value("${IOT_GATEWAY_MAX_BYTES_PER_TENANT:5000000000}") long limit)
      throws Exception {
    var credentials = List.of(passwordA, passwordB);
    var names = List.of("gateway-a", "gateway-b");
    var upload = registryEndpoint.resolve(TelemetryBatchLimits.PATH);
    spool = GatewaySpool.open(database, limit);
    try {
      var scopes = registries.scopes();
      for (int index = 0; index < scopes.size(); index++) {
        var registry = scopes.get(index);
        UUID tenant = registry.tenant();
        var snapshot = new AtomicReference<>(spool.stats(tenant));
        var overflow =
            meters.counter("iot.gateway.mqtt.inbox.overflow.events", "tenant", tenant.toString());
        var mqtt =
            new GatewayMqtt(
                mqttEndpoint, tenant, names.get(index), credentials.get(index), overflow);
        channels.add(
            new Channel(
                tenant,
                mqtt,
                new GatewayIngress(spool, registry, mqtt),
                new GatewayDelivery(spool, registry, http, upload),
                snapshot));
        registerMetrics(meters, tenant.toString(), snapshot);
      }
    } catch (MqttException | SQLException | RuntimeException failure) {
      stop();
      throw failure;
    }
  }

  @Scheduled(fixedDelay = 60)
  synchronized void tick() {
    long now = System.currentTimeMillis();
    for (var channel : channels) {
      try {
        channel.ingress().tick(now);
        channel.delivery().tick(now);
        if (now >= nextMetrics) {
          channel.metrics().set(spool.stats(channel.tenant()));
        }
      } catch (SQLException failure) {
        LOG.warn(
            "Gateway spool operation failed for tenant {} with SQLite error code {}",
            channel.tenant(),
            failure.getErrorCode());
      }
    }
    if (now >= nextMetrics) {
      nextMetrics = now + 1_000;
    }
  }

  private void registerMetrics(
      MeterRegistry meters, String tenant, AtomicReference<GatewaySpool.Stats> stats) {
    Gauge.builder("iot.gateway.spool.events", stats, ref -> ref.get().events())
        .tag("tenant", tenant)
        .register(meters);
    Gauge.builder("iot.gateway.spool.capacity.bytes", spool, GatewaySpool::capacity)
        .tag("tenant", tenant)
        .register(meters);
    Gauge.builder("iot.gateway.spool.bytes", stats, ref -> ref.get().bytes())
        .tag("tenant", tenant)
        .register(meters);
    Gauge.builder(
            "iot.gateway.spool.oldest.seconds",
            stats,
            ref -> {
              long observed = ref.get().oldestMs();
              return observed == 0
                  ? 0
                  : Math.max(0, System.currentTimeMillis() - observed) / 1000.0;
            })
        .tag("tenant", tenant)
        .register(meters);
    FunctionCounter.builder("iot.gateway.accepted.events", stats, ref -> ref.get().accepted())
        .tag("tenant", tenant)
        .register(meters);
    FunctionCounter.builder("iot.gateway.accepted.bytes", stats, ref -> ref.get().acceptedBytes())
        .tag("tenant", tenant)
        .register(meters);
    FunctionCounter.builder("iot.gateway.duplicate.events", stats, ref -> ref.get().duplicates())
        .tag("tenant", tenant)
        .register(meters);
    FunctionCounter.builder("iot.gateway.delivered.events", stats, ref -> ref.get().delivered())
        .tag("tenant", tenant)
        .register(meters);
    FunctionCounter.builder(
            "iot.gateway.backpressure.events", stats, ref -> ref.get().backpressure())
        .tag("tenant", tenant)
        .register(meters);
    FunctionCounter.builder("iot.gateway.rejected.events", stats, ref -> ref.get().rejected())
        .tag("tenant", tenant)
        .register(meters);
  }

  @PreDestroy
  synchronized void stop() {
    for (var channel : channels) {
      channel.delivery().stop();
      try {
        channel.mqtt().close();
      } catch (MqttException failure) {
        LOG.warn("Gateway MQTT shutdown failed with reason {}", failure.getReasonCode());
      }
    }
    http.shutdownNow();
    try {
      spool.close();
    } catch (SQLException failure) {
      LOG.warn("Gateway spool close failed with SQLite error code {}", failure.getErrorCode());
    }
  }

  private record Channel(
      UUID tenant,
      GatewayMqtt mqtt,
      GatewayIngress ingress,
      GatewayDelivery delivery,
      AtomicReference<GatewaySpool.Stats> metrics) {}
}
