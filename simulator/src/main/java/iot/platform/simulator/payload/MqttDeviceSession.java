package iot.platform.simulator.payload;

import com.google.protobuf.InvalidProtocolBufferException;
import iot.platform.control.CommandProtocol;
import iot.platform.control.proto.SamplingAcknowledgment;
import iot.platform.control.proto.SamplingCommand;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.TelemetryReceipt;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

final class MqttDeviceSession implements AutoCloseable {
  private final MqttAsyncClient client;
  private final MqttConnectionOptions options = new MqttConnectionOptions();
  private final SensorStream.Device device;
  private final String[] filters;
  private final Map<String, SensorStream> commandTopics;
  private final Map<SensorStream, SamplingAcknowledgment> state = new HashMap<>();
  private final LinkedHashSet<SensorStream> announce = new LinkedHashSet<>();
  private final ArrayBlockingQueue<Command> commands = new ArrayBlockingQueue<>(256);
  private final Map<String, SensorStream> receiptTopics;
  private final ArrayBlockingQueue<Receipt> receipts = new ArrayBlockingQueue<>(256);
  private IMqttToken connection;
  private IMqttToken subscription;
  private long nextAttempt;
  private int failures;

  MqttDeviceSession(
      String uri, SensorStream.Device device, String password, List<SensorStream> streams)
      throws MqttException {
    if (password.isBlank()) {
      throw new IllegalArgumentException(
          "A generated MQTT credential is required for each demo device");
    }
    this.device = device;
    filters = new String[] {device.topicPrefix() + "+/receipt", device.topicPrefix() + "+/command"};
    commandTopics =
        streams.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    stream -> stream.topic("command"), Function.identity()));
    receiptTopics =
        streams.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    stream -> stream.topic("receipt"), Function.identity()));
    options.setUserName(device.principal());
    options.setPassword(password.getBytes(StandardCharsets.UTF_8));
    // SQLite retries every undelivered application event on a fresh transport session.
    options.setCleanStart(true);
    options.setSessionExpiryInterval(0L);
    options.setConnectionTimeout(3);
    options.setKeepAliveInterval(10);
    options.setMaximumPacketSize(8192L);
    client = new MqttAsyncClient(uri, device.clientId(), new MemoryPersistence());
    client.setCallback(new BrokerCallback());
  }

  boolean ready(long now) {
    if (connection != null) {
      if (connection.getException() != null) {
        retryLater(now);
      } else if (!connection.isComplete()) {
        return false;
      }
      connection = null;
    }
    if (now < nextAttempt) {
      return false;
    }
    try {
      if (!client.isConnected()) {
        subscription = null;
        announce.addAll(state.keySet());
        connection = client.connect(options);
        return false;
      }
      if (subscription == null) {
        subscription = client.subscribe(filters, new int[] {1, 1});
        return false;
      }
      if (subscription.getException() != null
          || (subscription.isComplete()
              && Arrays.stream(subscription.getReasonCodes()).anyMatch(code -> code >= 128))) {
        subscription = null;
        retryLater(now);
        return false;
      }
      if (!subscription.isComplete()) {
        return false;
      }
      failures = 0;
      return true;
    } catch (MqttException unavailable) {
      retryLater(now);
      return false;
    }
  }

  private void retryLater(long now) {
    long delay = Math.min(30_000L, 500L << Math.min(failures++, 6));
    nextAttempt = now + delay + Math.floorMod(device.clientId().hashCode() ^ failures, 250);
  }

  void restore(SimulatorOutbox outbox) throws SQLException {
    for (var stream : commandTopics.values()) {
      var prior = outbox.sampling(stream);
      if (prior != null) {
        state.put(stream, prior);
      }
    }
  }

  boolean enabled(SensorStream stream) {
    var applied = state.get(stream);
    return applied == null || applied.getEnabled();
  }

  long appliedVersion(SensorStream stream) {
    var applied = state.get(stream);
    return applied == null ? 0 : applied.getVersion();
  }

  void flush(SimulatorOutbox outbox, long now) throws SQLException {
    for (int handled = 0; handled < 256; handled++) {
      var receipt = receipts.poll();
      if (receipt == null) {
        break;
      }
      outbox.acknowledge(receipt.stream(), receipt.payload());
    }
    for (int handled = 0; handled < 64; handled++) {
      var received = commands.poll();
      if (received == null) {
        break;
      }
      var applied = outbox.apply(received.stream(), received.value(), System.currentTimeMillis());
      if (applied != null) {
        state.put(received.stream(), applied);
        announce.add(received.stream());
      }
    }
    if (!ready(now)) {
      return;
    }
    var acknowledgments = announce.iterator();
    for (int sent = 0; sent < 16 && acknowledgments.hasNext(); sent++) {
      var stream = acknowledgments.next();
      if (!publish(stream.topic("command-ack"), state.get(stream).toByteArray(), 1)) {
        break;
      }
      acknowledgments.remove();
    }
    int available = Math.min(32, 64 - client.getPendingTokens().length);
    if (available <= 0) {
      return;
    }
    for (var event : outbox.due(device, now, available)) {
      if (!publish(device.topicPrefix() + event.sensorId() + "/telemetry", event.payload(), 1)) {
        break;
      }
    }
  }

  boolean publish(String topic, byte[] payload, int qos) {
    try {
      client.publish(topic, payload, qos, false);
      return true;
    } catch (MqttException unavailable) {
      return false;
    }
  }

  boolean connected() {
    return client.isConnected();
  }

  @Override
  public void close() throws MqttException {
    try {
      if (client.isConnected()) {
        client.disconnect(100).waitForCompletion(1000);
      }
    } finally {
      client.close(true);
    }
  }

  private record Command(SensorStream stream, SamplingCommand value) {}

  private final class BrokerCallback implements MqttCallback {
    @Override
    public void messageArrived(String topic, MqttMessage message) {
      SensorStream control = commandTopics.get(topic);
      if (control != null) {
        if (message.getQos() == 1
            && !message.isRetained()
            && message.getPayload().length <= CommandProtocol.MAX_PACKET_BYTES) {
          try {
            commands.offer(new Command(control, SamplingCommand.parseFrom(message.getPayload())));
          } catch (InvalidProtocolBufferException malformed) {
            // The authorized gateway retries pending commands until they expire.
          }
        }
        return;
      }
      SensorStream stream = receiptTopics.get(topic);
      if (stream == null
          || message.getQos() != 1
          || message.isRetained()
          || message.getPayload().length > TelemetryValidation.MAX_PAYLOAD_BYTES) {
        return;
      }
      try {
        receipts.offer(new Receipt(stream, TelemetryReceipt.parseFrom(message.getPayload())));
      } catch (InvalidProtocolBufferException malformed) {
        // Ignoring an invalid receipt leaves the durable event eligible for retry.
      }
    }

    @Override
    public void disconnected(MqttDisconnectResponse response) {}

    @Override
    public void mqttErrorOccurred(MqttException error) {}

    @Override
    public void deliveryComplete(IMqttToken token) {
      // PUBACK belongs to the broker transport and never changes the durable outbox.
    }

    @Override
    public void connectComplete(boolean reconnected, String uri) {}

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {}
  }

  private record Receipt(SensorStream stream, TelemetryReceipt payload) {}
}
