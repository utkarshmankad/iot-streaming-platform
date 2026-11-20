package iot.platform.gateway.ingest;

import com.google.protobuf.InvalidProtocolBufferException;
import iot.platform.control.CommandProtocol;
import iot.platform.control.proto.PendingCommands;
import iot.platform.control.proto.SamplingAcknowledgment;
import iot.platform.gateway.registry.RegistryCache;
import iot.platform.registry.proto.RegistrySnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class GatewayCommands {
  private static final long POLL_MS = 500;
  private final GatewaySpool spool;
  private final RegistryCache registry;
  private final GatewayMqtt mqtt;
  private final HttpClient http;
  private final URI endpoint;
  private final IngressRegistry assignments = new IngressRegistry();
  private CompletableFuture<HttpResponse<byte[]>> polls;
  private CompletableFuture<HttpResponse<Void>> acknowledgments;
  private String inFlight;
  private long nextPoll;
  private long nextAck;

  GatewayCommands(
      GatewaySpool spool, RegistryCache registry, GatewayMqtt mqtt, HttpClient http, URI endpoint) {
    this.spool = spool;
    this.registry = registry;
    this.mqtt = mqtt;
    this.http = http;
    this.endpoint = endpoint;
  }

  void tick(long now) throws SQLException {
    var snapshot = registry.current().orElse(null);
    for (int checked = 0; checked < 32; checked++) {
      var inbound = mqtt.commandAck();
      if (inbound == null) {
        break;
      }
      bufferAck(snapshot, inbound);
    }
    if (acknowledgments != null && acknowledgments.isDone()) {
      finishAck(now);
      acknowledgments = null;
    }
    if (polls != null && polls.isDone()) {
      dispatch(snapshot, now);
      polls = null;
    }
    if (snapshot == null) {
      return;
    }
    if (acknowledgments == null && now >= nextAck) {
      var ack = spool.nextAck(registry.tenant());
      if (ack != null) {
        inFlight = ack.id();
        var request =
            request("/" + ack.id() + "/ack")
                .header("Content-Type", CommandProtocol.CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(ack.payload()));
        acknowledgments = http.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding());
      }
    }
    if (polls == null && now >= nextPoll) {
      nextPoll = now + POLL_MS;
      polls =
          http.sendAsync(
              request("/pending").header("Accept", CommandProtocol.CONTENT_TYPE).GET().build(),
              ignored -> new BoundedResponse(CommandProtocol.MAX_PAGE_BYTES));
    }
  }

  private HttpRequest.Builder request(String suffix) {
    var request =
        HttpRequest.newBuilder(URI.create(endpoint + suffix)).timeout(Duration.ofSeconds(4));
    registry.authorize(request);
    return request;
  }

  private void bufferAck(RegistrySnapshot snapshot, GatewayMqtt.Incoming inbound)
      throws SQLException {
    if (inbound.payload().length > CommandProtocol.MAX_PACKET_BYTES || snapshot == null) {
      return;
    }
    var prefix = inbound.topic().substring(0, inbound.topic().lastIndexOf('/') + 1);
    var sensor = assignments.resolve(snapshot, prefix + "telemetry");
    if (sensor == null) {
      return;
    }
    try {
      var ack = SamplingAcknowledgment.parseFrom(inbound.payload());
      if (CommandProtocol.valid(ack) && sensor.sensor().toString().equals(ack.getSensorId())) {
        spool.queueAck(registry.tenant(), ack.getCommandId(), inbound.payload());
      }
    } catch (InvalidProtocolBufferException malformed) {
      // The pending command will be retried while it remains valid.
    }
  }

  private void finishAck(long now) throws SQLException {
    try {
      int status = acknowledgments.join().statusCode();
      if (status == 204 || status == 400 || status == 404 || status == 409) {
        spool.finishAck(registry.tenant(), inFlight);
        return;
      }
    } catch (CompletionException unavailable) {
      // Keep the original acknowledgment on disk until a terminal HTTP response.
    }
    nextAck = now + POLL_MS;
  }

  private void dispatch(RegistrySnapshot snapshot, long now) {
    if (snapshot == null) {
      return;
    }
    try {
      var response = polls.join();
      if (response.statusCode() != 200
          || !response
              .headers()
              .firstValue("content-type")
              .orElse("")
              .equals(CommandProtocol.CONTENT_TYPE)) {
        return;
      }
      var page = PendingCommands.parseFrom(response.body());
      if (page.getCommandsCount() > CommandProtocol.MAX_PENDING) {
        return;
      }
      for (var entry : page.getCommandsList()) {
        var command = entry.getCommand();
        if (!CommandProtocol.valid(command)
            || !CommandProtocol.canonicalUuid(entry.getDeviceId())
            || now >= command.getExpiresAtMs()) {
          continue;
        }
        String prefix =
            "iot/v1/"
                + registry.tenant()
                + "/"
                + entry.getDeviceId()
                + "/"
                + command.getSensorId()
                + "/";
        if (assignments.resolve(snapshot, prefix + "telemetry") != null) {
          mqtt.command(prefix + "command", command.toByteArray());
        }
      }
    } catch (CompletionException | InvalidProtocolBufferException unavailable) {
      // Telemetry and the durable ACK queue continue while command polling is unavailable.
    }
  }

  void stop() {
    if (polls != null) {
      polls.cancel(true);
    }
    if (acknowledgments != null) {
      acknowledgments.cancel(true);
    }
  }
}
