package iot.platform.gateway.ingest;

import com.google.protobuf.InvalidProtocolBufferException;
import iot.platform.gateway.registry.RegistryCache;
import iot.platform.registry.proto.RegistrySnapshot;
import iot.platform.telemetry.HeartbeatBatchLimits;
import iot.platform.telemetry.TelemetryBatchLimits;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.SensorHeartbeatBatch;
import iot.platform.telemetry.proto.SensorHeartbeatObservation;
import iot.platform.telemetry.proto.TelemetryHeartbeat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class GatewayHeartbeats {
  private final RegistryCache registry;
  private final GatewayMqtt mqtt;
  private final HttpClient http;
  private final URI endpoint;
  private final IngressRegistry assignments = new IngressRegistry();
  private final LinkedHashMap<UUID, SensorHeartbeatObservation> pending = new LinkedHashMap<>();
  private RegistrySnapshot observedSnapshot;
  private CompletableFuture<HttpResponse<Void>> sending;
  private long next;

  GatewayHeartbeats(RegistryCache registry, GatewayMqtt mqtt, HttpClient http, URI endpoint) {
    this.registry = registry;
    this.mqtt = mqtt;
    this.http = http;
    this.endpoint = endpoint;
  }

  void tick(long now) {
    var snapshot = registry.current().orElse(null);
    if (snapshot != observedSnapshot) {
      pending.clear();
      observedSnapshot = snapshot;
    }
    for (int i = 0; i < 64; i++) {
      var inbound = mqtt.heartbeat();
      if (inbound == null) {
        break;
      }
      collect(snapshot, inbound, now);
    }
    if (pending.isEmpty() || now < next || (sending != null && !sending.isDone())) {
      return;
    }
    next = now + 500;
    byte[] body =
        SensorHeartbeatBatch.newBuilder()
            .addAllObservations(pending.values())
            .build()
            .toByteArray();
    pending.clear();
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", TelemetryBatchLimits.CONTENT_TYPE)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    registry.authorize(request);
    // Heartbeats describe current liveness. The next live report supersedes a lost batch.
    sending = http.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding());
  }

  private void collect(RegistrySnapshot snapshot, GatewayMqtt.Incoming inbound, long now) {
    if (snapshot == null || inbound.payload().length > TelemetryValidation.MAX_PAYLOAD_BYTES) {
      return;
    }
    var topic = inbound.topic().substring(0, inbound.topic().lastIndexOf('/') + 1) + "telemetry";
    var route = assignments.resolve(snapshot, topic);
    if (route == null) {
      return;
    }
    try {
      var value = TelemetryHeartbeat.parseFrom(inbound.payload());
      if (value.getSchemaVersion() != TelemetryValidation.VERSION
          || !route.sensor().toString().equals(value.getSensorId())
          || value.getObservedAtMs() <= now - HeartbeatBatchLimits.OFFLINE_MS
          || value.getObservedAtMs() > now + HeartbeatBatchLimits.FUTURE_MS) {
        return;
      }
      var existing = pending.get(route.sensor());
      if (existing == null && pending.size() >= HeartbeatBatchLimits.MAX_OBSERVATIONS) {
        return;
      }
      if (existing == null || value.getObservedAtMs() > existing.getHeartbeat().getObservedAtMs()) {
        pending.put(
            route.sensor(),
            SensorHeartbeatObservation.newBuilder()
                .setDeviceId(route.device())
                .setHeartbeat(value)
                .build());
      }
    } catch (InvalidProtocolBufferException invalid) {
      // Only a valid observation can update liveness.
    }
  }

  void stop() {
    if (sending != null) {
      sending.cancel(true);
    }
  }
}
