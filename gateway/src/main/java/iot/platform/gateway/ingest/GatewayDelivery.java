package iot.platform.gateway.ingest;

import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.Counter;
import iot.platform.gateway.registry.RegistryCache;
import iot.platform.telemetry.TelemetryBatchLimits;
import iot.platform.telemetry.proto.TelemetryBatch;
import iot.platform.telemetry.proto.TelemetryBatchEntry;
import iot.platform.telemetry.proto.TelemetryBatchResponse;
import iot.platform.telemetry.proto.TelemetryBatchResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GatewayDelivery {
  private static final Logger LOG = LoggerFactory.getLogger(GatewayDelivery.class);
  private static final Pattern REASON = Pattern.compile("[a-z0-9-]{1,64}");
  private final GatewaySpool spool;
  private final RegistryCache registry;
  private final HttpClient http;
  private final URI endpoint;
  private final Counter retries;
  private Flight pending;
  private int batchLimit = TelemetryBatchLimits.MAX_ENTRIES;
  private int failures;
  private long retryAt;
  private boolean preferReplay;

  GatewayDelivery(
      GatewaySpool spool, RegistryCache registry, HttpClient http, URI endpoint, Counter retries) {
    this.spool = spool;
    this.registry = registry;
    this.http = http;
    this.endpoint = endpoint;
    this.retries = retries;
  }

  void tick(long now) throws SQLException {
    if (pending != null) {
      if (!pending.response().isDone()) {
        return;
      }
      settle(pending, now);
      pending = null;
    }
    if (now < retryAt || registry.current().isEmpty()) {
      return;
    }
    var events = spool.next(registry.tenant(), now, batchLimit, preferReplay);
    if (events.isEmpty()
        || (events.size() < batchLimit
            && events.stream().allMatch(row -> row.receivedMs() > now - 1000))) {
      return;
    }
    var batch = TelemetryBatch.newBuilder();
    for (var row : events) {
      batch.addEntries(
          TelemetryBatchEntry.newBuilder()
              .setCorrelationId(Long.toString(row.id()))
              .setTenantId(registry.tenant().toString())
              .setDeviceId(row.deviceId())
              .setSensorId(row.sensorId())
              .setPayload(row.payload())
              .setGatewayReceivedAtMs(row.receivedMs()));
    }
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", TelemetryBatchLimits.CONTENT_TYPE)
            .header("Accept", TelemetryBatchLimits.CONTENT_TYPE);
    registry.authorize(request);
    // The entry and wire size bounds leave room for all topic IDs and correlation metadata.
    byte[] body = batch.build().toByteArray();
    if (body.length > TelemetryBatchLimits.MAX_REQUEST_BYTES) {
      split(events);
      return;
    }
    var transport =
        http.sendAsync(
            request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            info -> new BoundedResponse(TelemetryBatchLimits.MAX_RESPONSE_BYTES));
    var future = transport.copy().orTimeout(6, TimeUnit.SECONDS);
    future.whenComplete(
        (response, failure) -> {
          if (failure instanceof TimeoutException || future.isCancelled()) {
            transport.cancel(true);
          }
        });
    pending = new Flight(events, future);
    preferReplay = !preferReplay;
  }

  private void settle(Flight flight, long now) throws SQLException {
    HttpResponse<byte[]> response;
    try {
      response = flight.response().join();
    } catch (CompletionException unavailable) {
      retry(flight.entries(), now, "request-failed");
      return;
    }
    if (response.statusCode() == 413) {
      split(flight.entries());
      return;
    }
    String mediaType =
        response.headers().firstValue("content-type").orElse("").split(";", 2)[0].strip();
    if (response.statusCode() != 200
        || !mediaType.equalsIgnoreCase(TelemetryBatchLimits.CONTENT_TYPE)) {
      retry(flight.entries(), now, "http-" + response.statusCode());
      return;
    }
    List<GatewaySpool.Delivered> delivered;
    try {
      delivered = match(response.body(), flight.entries());
    } catch (InvalidProtocolBufferException | IllegalArgumentException invalid) {
      retry(flight.entries(), now, "invalid-batch-response");
      return;
    }
    spool.finish(registry.tenant(), delivered);
    failures = 0;
    retryAt = 0;
  }

  private void split(List<GatewaySpool.Queued> events) throws SQLException {
    if (events.size() == 1) {
      spool.finish(
          registry.tenant(),
          List.of(new GatewaySpool.Delivered(events.getFirst().id(), "batch-entry-too-large")));
    } else {
      batchLimit = Math.min(batchLimit, Math.max(1, events.size() / 2));
    }
  }

  private void retry(List<GatewaySpool.Queued> events, long now, String reason)
      throws SQLException {
    spool.retry(registry.tenant(), events, now);
    retries.increment();
    if (failures == 0) {
      LOG.warn(
          "Gateway batch delivery deferred for tenant {} with reason {}",
          registry.tenant(),
          reason);
    }
    long delay = Math.min(30_000, 1_000L << Math.min(failures++, 5));
    retryAt = now + delay + Math.floorMod(registry.tenant().hashCode() ^ failures, 250);
  }

  static List<GatewaySpool.Delivered> match(byte[] raw, List<GatewaySpool.Queued> entries)
      throws InvalidProtocolBufferException {
    var response = TelemetryBatchResponse.parseFrom(raw);
    Set<String> expected = new HashSet<>();
    for (var entry : entries) {
      expected.add(Long.toString(entry.id()));
    }
    var result = new ArrayList<GatewaySpool.Delivered>();
    for (var outcome : response.getResultsList()) {
      String id = outcome.getCorrelationId();
      if (!expected.remove(id)) {
        throw new IllegalArgumentException(
            "Batch response contains an unknown or repeated correlation");
      }
      result.add(new GatewaySpool.Delivered(Long.parseLong(id), rejection(outcome)));
    }
    if (!expected.isEmpty()) {
      throw new IllegalArgumentException("Batch response is missing outcomes");
    }
    return result;
  }

  private static String rejection(TelemetryBatchResult outcome) {
    return switch (outcome.getOutcome()) {
      case COMMITTED, DUPLICATE -> "";
      case REJECTED ->
          REASON.matcher(outcome.getReasonCode()).matches()
              ? outcome.getReasonCode()
              : "backend-rejected";
      default -> throw new IllegalArgumentException("Batch response contains an unknown outcome");
    };
  }

  void stop() {
    if (pending != null) {
      pending.response().cancel(true);
    }
  }

  private record Flight(
      List<GatewaySpool.Queued> entries, CompletableFuture<HttpResponse<byte[]>> response) {}
}
