package iot.platform.gateway.ingest;

import io.micrometer.core.instrument.Counter;
import iot.platform.telemetry.proto.TelemetryReceipt;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

final class GatewayMqtt implements AutoCloseable {
  private final MqttAsyncClient client;
  private final MqttConnectionOptions options = new MqttConnectionOptions();
  private final ArrayBlockingQueue<Incoming> messages = new ArrayBlockingQueue<>(2048);
  private final ArrayBlockingQueue<Incoming> control = new ArrayBlockingQueue<>(256);
  private final String[] filters;
  private final Counter overflow;
  private IMqttToken connection;
  private IMqttToken subscription;
  private long retryAt;
  private int failures;

  GatewayMqtt(String uri, UUID tenant, String principal, String password, Counter overflow)
      throws MqttException {
    if (password.isBlank()) {
      throw new IllegalArgumentException("A generated MQTT password is required for the gateway");
    }
    this.overflow = overflow;
    String prefix = "iot/v1/" + tenant + "/+/+/";
    filters = new String[] {prefix + "telemetry", prefix + "command-ack"};
    options.setUserName(principal);
    options.setPassword(password.getBytes(StandardCharsets.UTF_8));
    options.setCleanStart(true);
    options.setSessionExpiryInterval(0L);
    options.setConnectionTimeout(3);
    options.setKeepAliveInterval(10);
    options.setMaximumPacketSize(8192L);
    client = new MqttAsyncClient(uri, "iot-ingress-" + principal, new MemoryPersistence());
    client.setCallback(new Subscriber());
  }

  void advance(long now) {
    if (connection != null) {
      if (connection.getException() != null) {
        retryLater(now);
      } else if (!connection.isComplete()) {
        return;
      }
      connection = null;
    }
    if (now < retryAt) {
      return;
    }
    try {
      if (!client.isConnected()) {
        subscription = null;
        connection = client.connect(options);
      } else if (subscription == null) {
        subscription = client.subscribe(filters, new int[] {1, 1});
      } else if (subscription.getException() != null
          || (subscription.isComplete()
              && Arrays.stream(subscription.getReasonCodes()).anyMatch(code -> code != 1))) {
        subscription = null;
        retryLater(now);
      } else if (subscription.isComplete()) {
        failures = 0;
      }
    } catch (MqttException unavailable) {
      retryLater(now);
    }
  }

  private void retryLater(long now) {
    long delay = Math.min(30_000L, 500L << Math.min(failures++, 6));
    retryAt = now + delay + Math.floorMod(client.getClientId().hashCode() ^ failures, 250);
  }

  Incoming poll() {
    return messages.poll();
  }

  Incoming commandAck() {
    return control.poll();
  }

  void command(String topic, byte[] payload) {
    if (client.getPendingTokens().length >= 48) {
      return;
    }
    try {
      client.publish(topic, payload, 1, false);
    } catch (MqttException unavailable) {
      // Backend polling retries unacknowledged commands within the validity window.
    }
  }

  void receipt(String telemetryTopic, TelemetryReceipt value) {
    String receiptTopic =
        telemetryTopic.substring(0, telemetryTopic.lastIndexOf('/') + 1) + "receipt";
    try {
      client.publish(receiptTopic, value.toByteArray(), 1, false);
    } catch (MqttException unavailable) {
      // The simulator keeps the original wire event and will retry.
    }
  }

  @Override
  public void close() throws MqttException {
    try {
      if (client.isConnected()) {
        client.disconnect(100).waitForCompletion(1_000);
      }
    } finally {
      client.close(true);
    }
  }

  private final class Subscriber implements MqttCallback {
    @Override
    public void messageArrived(String topic, MqttMessage message) {
      if (message.getQos() == 1 && !message.isRetained()) {
        // Keep acknowledgement traffic from competing for room in the telemetry inbox.
        var queue = topic.endsWith("/command-ack") ? control : messages;
        if (!queue.offer(new Incoming(topic, message.getPayload(), System.currentTimeMillis()))) {
          overflow.increment();
        }
      }
    }

    @Override
    public void disconnected(MqttDisconnectResponse response) {}

    @Override
    public void mqttErrorOccurred(MqttException error) {}

    @Override
    public void deliveryComplete(IMqttToken token) {}

    @Override
    public void connectComplete(boolean reconnected, String uri) {}

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {}
  }

  record Incoming(String topic, byte[] payload, long receivedMs) {}
}
