package iot.platform.gateway.registry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("registry")
@EnableScheduling
class GatewayRegistries {
  private static final Logger LOG = LoggerFactory.getLogger(GatewayRegistries.class);
  private final List<RegistryCache> caches;

  GatewayRegistries(
      @Value("${IOT_REGISTRY_URL}") URI endpoint,
      @Value("${IOT_REGISTRY_GATEWAY_A_TOKEN}") String tokenA,
      @Value("${IOT_REGISTRY_GATEWAY_B_TOKEN}") String tokenB,
      @Value("${IOT_REGISTRY_CACHE:/var/lib/iot/registry}") Path directory) {
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var gateway = UUID.fromString("00000000-0000-4000-8000-000000000001");
    caches =
        List.of(
            new RegistryCache(
                client,
                endpoint,
                tokenA,
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                gateway,
                directory.resolve("demo-a.pb")),
            new RegistryCache(
                client,
                endpoint,
                tokenB,
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                gateway,
                directory.resolve("demo-b.pb")));
  }

  @Scheduled(fixedDelayString = "${IOT_REGISTRY_REFRESH_INTERVAL:PT30S}")
  void refresh() {
    for (var cache : caches) {
      try {
        cache.refresh();
      } catch (IOException unavailable) {
        LOG.warn("Registry refresh failed: {}", unavailable.getMessage());
      } catch (InterruptedException cancelled) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
