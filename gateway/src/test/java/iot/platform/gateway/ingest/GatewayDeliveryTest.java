package iot.platform.gateway.ingest;

import static iot.platform.gateway.ingest.GatewaySpoolTest.TENANT_A;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import iot.platform.gateway.registry.RegistryCache;
import iot.platform.registry.proto.RegistrySnapshot;
import iot.platform.telemetry.TelemetryBatchLimits;
import iot.platform.telemetry.proto.TelemetryBatch;
import iot.platform.telemetry.proto.TelemetryBatchResponse;
import iot.platform.telemetry.proto.TelemetryBatchResult;
import iot.platform.telemetry.proto.TelemetryBatchResult.Outcome;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayDeliveryTest {
  private static final long NOW = 1_800_000_000_000L;
  private static final UUID GATEWAY = new UUID(0, 1);
  private static final String FIXTURE_AUTHORIZATION = "Bearer gateway-http-fixture";
  @TempDir Path directory;

  @Test
  void checksEveryCorrelationAndRejectsMissingRepeatedAndUnknownOutcomes() throws Exception {
    var queued =
        List.of(
            new GatewaySpool.Queued(9, "device", "sensor", ByteString.EMPTY, NOW, 0),
            new GatewaySpool.Queued(10, "device", "sensor", ByteString.EMPTY, NOW, 0));
    var success = outcome("9", Outcome.DUPLICATE, "");
    var rejected = outcome("10", Outcome.REJECTED, "outside-retention");
    var response = TelemetryBatchResponse.newBuilder().addResults(rejected).addResults(success);
    assertEquals(
        List.of(
            new GatewaySpool.Delivered(10, "outside-retention"), new GatewaySpool.Delivered(9, "")),
        GatewayDelivery.match(response.build().toByteArray(), queued));
    assertEquals(
        "backend-rejected",
        GatewayDelivery.match(
                response
                    .setResults(0, outcome("10", Outcome.REJECTED, "arbitrary detail \n"))
                    .build()
                    .toByteArray(),
                queued)
            .getFirst()
            .rejection());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayDelivery.match(
                response
                    .setResults(0, outcome("10", Outcome.OUTCOME_UNSPECIFIED, ""))
                    .build()
                    .toByteArray(),
                queued));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayDelivery.match(
                response.setResults(0, outcome("11", Outcome.COMMITTED, "")).build().toByteArray(),
                queued));
    assertThrows(
        IllegalArgumentException.class,
        () -> GatewayDelivery.match(response.setResults(0, success).build().toByteArray(), queued));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayDelivery.match(
                TelemetryBatchResponse.getDefaultInstance().toByteArray(), queued));
    assertThrows(
        InvalidProtocolBufferException.class, () -> GatewayDelivery.match(new byte[] {8}, queued));
  }

  @Test
  void batchesAtCountOrAgeAndRetransmitsUnchangedAfterAmbiguousResults() throws Exception {
    var requests = new LinkedBlockingQueue<TelemetryBatch>();
    var action = new AtomicReference<>(Response.OVERLOADED);
    withBackend(
        requests,
        action,
        (client, registry, endpoint) -> {
          try (var spool = GatewaySpool.open(directory.resolve("delivery.sqlite"), 100_000)) {
            var delivery =
                new GatewayDelivery(
                    spool,
                    registry,
                    client,
                    endpoint,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
                        .counter("retry"));
            var entries =
                java.util.stream.IntStream.rangeClosed(1, 250)
                    .mapToObj(id -> GatewaySpoolTest.reading(id, "reading-" + id, NOW))
                    .toList();
            spool.admit(TENANT_A, entries);
            delivery.tick(NOW);
            var first = batch(requests);
            assertEquals(250, first.getEntriesCount());
            assertEquals(NOW, first.getEntries(0).getGatewayReceivedAtMs());
            assertEquals(TENANT_A.toString(), first.getEntries(0).getTenantId());
            spin(
                () -> {
                  delivery.tick(NOW + 100);
                  return spool.next(TENANT_A, NOW + 100, 250, false).isEmpty();
                });
            assertEquals(250, spool.stats(TENANT_A).events());

            action.set(Response.OVERSIZED_RESPONSE);
            delivery.tick(NOW + 1500);
            var second = batch(requests);
            assertEquals(first, second);
            spin(
                () -> {
                  delivery.tick(NOW + 1500);
                  return spool.next(TENANT_A, NOW + 1500, 250, false).isEmpty();
                });
            assertEquals(250, spool.stats(TENANT_A).events());

            action.set(Response.MALFORMED_RESPONSE);
            delivery.tick(NOW + 4500);
            assertEquals(first, batch(requests));
            spin(
                () -> {
                  delivery.tick(NOW + 4500);
                  return spool.next(TENANT_A, NOW + 4500, 250, false).isEmpty();
                });

            action.set(Response.COMMITTED);
            delivery.tick(NOW + 9500);
            assertEquals(first, batch(requests));
            spin(
                () -> {
                  delivery.tick(NOW + 9500);
                  return spool.stats(TENANT_A).events() == 0;
                });
            assertEquals(250, spool.stats(TENANT_A).delivered());

            spool.admit(TENANT_A, List.of(GatewaySpoolTest.reading(260, "partial", NOW + 9500)));
            delivery.tick(NOW + 10000);
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS));
            delivery.tick(NOW + 10500);
            assertEquals(1, batch(requests).getEntriesCount());
            spin(
                () -> {
                  delivery.tick(NOW + 10500);
                  return spool.stats(TENANT_A).events() == 0;
                });
            delivery.stop();
          }
        });
  }

  @Test
  void splitsHttp413AndAccountsForSingleOversizedEntryAndBackendRejection() throws Exception {
    var requests = new LinkedBlockingQueue<TelemetryBatch>();
    var action = new AtomicReference<>(Response.REQUEST_TOO_LARGE);
    withBackend(
        requests,
        action,
        (client, registry, endpoint) -> {
          try (var spool = GatewaySpool.open(directory.resolve("split.sqlite"), 1000)) {
            var delivery =
                new GatewayDelivery(
                    spool,
                    registry,
                    client,
                    endpoint,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
                        .counter("retry"));
            spool.admit(
                TENANT_A,
                List.of(
                    GatewaySpoolTest.reading(1, "first", NOW - 2000),
                    GatewaySpoolTest.reading(2, "second", NOW - 1000)));
            delivery.tick(NOW);
            assertEquals(2, batch(requests).getEntriesCount());
            spin(
                () -> {
                  delivery.tick(NOW);
                  return !requests.isEmpty();
                });
            assertEquals(1, batch(requests).getEntriesCount());

            action.set(Response.REJECTED);
            spin(
                () -> {
                  delivery.tick(NOW);
                  return spool.stats(TENANT_A).events() == 1;
                });
            action.set(Response.REJECTED);
            assertEquals(1, batch(requests).getEntriesCount());
            spin(
                () -> {
                  delivery.tick(NOW);
                  return spool.stats(TENANT_A).events() == 0;
                });
            assertEquals(2, spool.stats(TENANT_A).rejected());
            assertEquals(0, spool.stats(TENANT_A).delivered());
          }
        });
  }

  private void withBackend(
      LinkedBlockingQueue<TelemetryBatch> requests,
      AtomicReference<Response> action,
      TestBackend verify)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] scoped =
        RegistrySnapshot.newBuilder()
            .setFormatVersion(1)
            .setTenantId(TENANT_A.toString())
            .setGatewayId(GATEWAY.toString())
            .build()
            .toByteArray();
    String etag =
        "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scoped)) + "\"";
    server.createContext(
        "/registry",
        exchange -> {
          try (exchange) {
            exchange.getResponseHeaders().set("ETag", etag);
            reply(exchange, 200, scoped);
          }
        });
    server.createContext(
        TelemetryBatchLimits.PATH,
        exchange -> {
          try (exchange) {
            if (!FIXTURE_AUTHORIZATION.equals(
                    exchange.getRequestHeaders().getFirst("Authorization"))
                || !TelemetryBatchLimits.CONTENT_TYPE.equals(
                    exchange.getRequestHeaders().getFirst("Content-Type"))) {
              reply(exchange, 401, new byte[0]);
              return;
            }
            var batch = TelemetryBatch.parseFrom(exchange.getRequestBody());
            var status = action.get();
            requests.add(batch);
            switch (status) {
              case OVERLOADED -> reply(exchange, 503, new byte[0]);
              case REQUEST_TOO_LARGE -> reply(exchange, 413, new byte[0]);
              case OVERSIZED_RESPONSE ->
                  reply(exchange, 200, new byte[TelemetryBatchLimits.MAX_RESPONSE_BYTES + 1]);
              case MALFORMED_RESPONSE -> reply(exchange, 200, new byte[] {8});
              case COMMITTED, REJECTED -> {
                var response = TelemetryBatchResponse.newBuilder();
                batch
                    .getEntriesList()
                    .forEach(
                        entry ->
                            response.addResults(
                                outcome(
                                    entry.getCorrelationId(),
                                    status == Response.COMMITTED
                                        ? Outcome.COMMITTED
                                        : Outcome.REJECTED,
                                    "fixture-rejected")));
                reply(exchange, 200, response.build().toByteArray());
              }
            }
          }
        });
    server.start();
    try (var client = HttpClient.newHttpClient()) {
      URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      var registry =
          new RegistryCache(
              client,
              origin.resolve("/registry"),
              "gateway-http-fixture",
              TENANT_A,
              GATEWAY,
              directory.resolve("scope.pb"));
      registry.refresh();
      verify.run(client, registry, origin.resolve(TelemetryBatchLimits.PATH));
    } finally {
      server.stop(0);
    }
  }

  private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", TelemetryBatchLimits.CONTENT_TYPE);
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      exchange.getResponseBody().write(body);
    }
  }

  private static TelemetryBatchResult outcome(String id, Outcome outcome, String reason) {
    return TelemetryBatchResult.newBuilder()
        .setCorrelationId(id)
        .setOutcome(outcome)
        .setReasonCode(reason)
        .build();
  }

  private static TelemetryBatch batch(LinkedBlockingQueue<TelemetryBatch> requests)
      throws Exception {
    var observed = requests.poll(5, TimeUnit.SECONDS);
    assertNotNull(observed, "Expected an HTTP batch");
    return observed;
  }

  private static void spin(CheckedCheck check) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(6).toNanos();
    while (System.nanoTime() < until) {
      if (check.passed()) {
        return;
      }
      Thread.sleep(25);
    }
    fail("Timed out waiting for the HTTP response to be processed");
  }

  private interface CheckedCheck {
    boolean passed() throws Exception;
  }

  private interface TestBackend {
    void run(HttpClient client, RegistryCache registry, URI endpoint) throws Exception;
  }

  private enum Response {
    OVERLOADED,
    REQUEST_TOO_LARGE,
    OVERSIZED_RESPONSE,
    MALFORMED_RESPONSE,
    COMMITTED,
    REJECTED
  }
}
