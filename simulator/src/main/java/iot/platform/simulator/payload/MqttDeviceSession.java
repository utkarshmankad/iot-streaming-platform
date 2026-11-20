package iot.platform.simulator.payload;

import com.google.protobuf.InvalidProtocolBufferException;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.TelemetryReceipt;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
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
  private final String receiptFilter;
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
    receiptFilter = device.topicPrefix() + "+/receipt";
    receiptTopics =
        streams.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    stream -> stream.topic("receipt"), Function.identity()));
    options.setUserName(device.principal());
    options.setPassword(password.getBytes(StandardCharsets.UTF_8));
    options.setCleanStart(false);
    options.setSessionExpiryInterval(604_800L);
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
        connection = client.connect(options);
        return false;
      }
      if (subscription == null) {
        subscription = client.subscribe(receiptFilter, 1);
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

  void flush(SimulatorOutbox outbox, long now) throws SQLException {
    for (int handled = 0; handled < 256; handled++) {
      var receipt = receipts.poll();
      if (receipt == null) {
        break;
      }
      outbox.acknowledge(receipt.stream(), receipt.payload());
    }
    if (!ready(now)) {
      return;
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

  private final class BrokerCallback implements MqttCallback {
    @Override
    public void messageArrived(String topic, MqttMessage message) {
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
