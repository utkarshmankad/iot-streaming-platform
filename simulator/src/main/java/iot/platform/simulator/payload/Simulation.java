package iot.platform.simulator.payload;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import iot.platform.telemetry.TelemetryValidation;
import iot.platform.telemetry.proto.TelemetryHeartbeat;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("simulate")
@EnableScheduling
final class Simulation {
  private static final Logger LOG = LoggerFactory.getLogger(Simulation.class);
  private static final long HEARTBEAT_MS = 5000;

  private final Clock clock = Clock.systemUTC();
  private final SimulatorOutbox outbox;
  private final List<SensorStream> streams;
  private final Map<SensorStream.Device, MqttDeviceSession> sessions = new LinkedHashMap<>();
  private final Map<UUID, AtomicReference<SimulatorOutbox.Stats>> statistics =
      new LinkedHashMap<>();
  private final Map<UUID, AtomicLong> receiptTimes = new LinkedHashMap<>();
  private final long maximumBytes;
  private final long seed;
  private final Path faultSilence;
  private final Path faultCommandAck;
  private long previousTick;
  private long nextMetrics;

  Simulation(
      @Value("${IOT_SIM_ASSIGNMENTS:/run/iot-registry/simulator.tsv}") Path assignments,
      @Value("${IOT_SIM_OUTBOX:/var/lib/iot/simulator.sqlite}") Path database,
      @Value("${IOT_SIM_MQTT_URI:tcp://mosquitto:1883}") String uri,
      @Value("${IOT_SIM_DEVICE_A_PASSWORD:}") String deviceA,
      @Value("${IOT_SIM_DEVICE_B_PASSWORD:}") String deviceB,
      @Value("${IOT_SIM_SEED:20260301}") long seed,
      @Value("${IOT_SIM_MAX_BYTES_PER_TENANT:5000000000}") long maximumBytes,
      MeterRegistry meters,
      Environment environment)
      throws Exception {
    this.seed = seed;
    this.maximumBytes = maximumBytes;
    streams = SensorStream.read(assignments);
    var passwords = Map.of("device-a", deviceA, "device-b", deviceB);
    boolean faultTest = environment.matchesProfiles("fault-test");
    boolean pilot = environment.matchesProfiles("pilot-fault");
    faultSilence = faultTest || pilot ? database.resolveSibling("fault-silence") : null;
    faultCommandAck = faultTest ? database.resolveSibling("fault-command-ack") : null;
    outbox = SimulatorOutbox.open(database, maximumBytes, faultTest || pilot);
    try {
      for (var entry :
          streams.stream().collect(Collectors.groupingBy(SensorStream::device)).entrySet()) {
        var device = entry.getKey();
        var session =
            new MqttDeviceSession(
                uri, device, passwords.getOrDefault(device.principal(), ""), entry.getValue());
        sessions.put(device, session);
        session.restore(outbox);
        if (!statistics.containsKey(device.tenantId())) {
          registerMetrics(meters, device.tenantId());
        }
      }
    } catch (MqttException | SQLException | RuntimeException failure) {
      stop();
      throw failure;
    }
  }

  @Scheduled(fixedDelay = 50)
  synchronized void tick() throws SQLException {
    long now = clock.millis();
    boolean withholdCommandAck = faultCommandAck != null && Files.exists(faultCommandAck);
    for (var session : sessions.values()) {
      session.flush(outbox, now, withholdCommandAck);
    }
    if (faultSilence == null || !Files.exists(faultSilence)) {
      var events = new ArrayList<SimulatorOutbox.Intent>();
      for (var stream : streams) {
        long observed = stream.observationSlot(now);
        if (observed > previousTick && sessions.get(stream.device()).enabled(stream)) {
          events.add(new SimulatorOutbox.Intent(stream, observed));
        }
      }
      if (!events.isEmpty()) {
        outbox.generate(events, seed);
      }
    }
    for (var stream : streams) {
      var session = sessions.get(stream.device());
      if (session.connected() && stream.slot(now, HEARTBEAT_MS) > previousTick) {
        var heartbeat =
            TelemetryHeartbeat.newBuilder()
                .setSchemaVersion(TelemetryValidation.VERSION)
                .setSensorId(stream.sensorId().toString())
                .setObservedAtMs(now)
                .setEnabled(session.enabled(stream))
                .setAppliedCommandVersion(session.appliedVersion(stream))
                .build();
        session.publish(stream.topic("heartbeat"), heartbeat.toByteArray(), 0);
      }
    }
    previousTick = now;

    if (now >= nextMetrics) {
      for (var entry : statistics.entrySet()) {
        var current = outbox.stats(entry.getKey());
        var previous = entry.getValue().getAndSet(current);
        if (current.pendingEvents() == 0
            || previous.pendingEvents() == 0
            || previous.accepted() != current.accepted()
            || previous.rejected() != current.rejected()) {
          receiptTimes.get(entry.getKey()).set(now);
        }
        if (current.dropped() > previous.dropped()) {
          LOG.warn(
              "Simulator outbox capacity reached for tenant {}, total dropped events {}",
              entry.getKey(),
              current.dropped());
        }
      }
      nextMetrics = now + HEARTBEAT_MS;
    }
  }

  private void registerMetrics(MeterRegistry meters, UUID tenant) throws SQLException {
    var snapshot = new AtomicReference<>(outbox.stats(tenant));
    statistics.put(tenant, snapshot);
    String id = tenant.toString();
    var receiptTime = new AtomicLong(clock.millis());
    receiptTimes.put(tenant, receiptTime);
    Gauge.builder(
            "iot.simulator.receipt.age.seconds",
            receiptTime,
            time -> Math.max(0, clock.millis() - time.get()) / 1000.0)
        .tag("tenant", id)
        .register(meters);
    Gauge.builder("iot.simulator.pending.capacity.bytes", this, sim -> sim.maximumBytes)
        .tag("tenant", id)
        .register(meters);
    FunctionCounter.builder(
            "iot.simulator.generated.events", snapshot, value -> value.get().generated())
        .tag("tenant", id)
        .register(meters);
    FunctionCounter.builder(
            "iot.simulator.generated.bytes", snapshot, value -> value.get().generatedBytes())
        .tag("tenant", id)
        .register(meters);
    FunctionCounter.builder(
            "iot.simulator.dropped.events", snapshot, value -> value.get().dropped())
        .tag("tenant", id)
        .register(meters);
    FunctionCounter.builder(
            "iot.simulator.accepted.events", snapshot, value -> value.get().accepted())
        .tag("tenant", id)
        .register(meters);
    FunctionCounter.builder(
            "iot.simulator.rejected.events", snapshot, value -> value.get().rejected())
        .tag("tenant", id)
        .register(meters);
    Gauge.builder("iot.simulator.pending.events", snapshot, value -> value.get().pendingEvents())
        .tag("tenant", id)
        .register(meters);
    Gauge.builder("iot.simulator.pending.bytes", snapshot, value -> value.get().pendingBytes())
        .tag("tenant", id)
        .register(meters);
  }

  @PreDestroy
  synchronized void stop() {
    for (var session : sessions.values()) {
      try {
        session.close();
      } catch (MqttException failure) {
        LOG.warn("Simulator MQTT session close failed with reason {}", failure.getReasonCode());
      }
    }
    try {
      outbox.close();
    } catch (SQLException failure) {
      LOG.warn("Simulator outbox close failed with SQL state {}", failure.getSQLState());
    }
  }
}
