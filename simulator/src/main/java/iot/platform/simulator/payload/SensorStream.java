package iot.platform.simulator.payload;

import iot.platform.telemetry.SensorType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record SensorStream(Device device, UUID sensorId, SensorType type, int periodMs) {
  SensorStream {
    if (periodMs <= 0) {
      throw new IllegalArgumentException("Sampling period must be positive");
    }
  }

  String topic(String kind) {
    return device.topicPrefix() + sensorId + "/" + kind;
  }

  long observationSlot(long nowMs) {
    return slot(nowMs, periodMs);
  }

  long slot(long nowMs, long interval) {
    long phase = Math.floorMod(Objects.hash(device.tenantId(), sensorId), interval);
    return Math.floorDiv(nowMs - phase, interval) * interval + phase;
  }

  static List<SensorStream> read(Path path) throws IOException {
    var keys = new HashSet<List<UUID>>();
    return Files.readAllLines(path).stream()
        .map(
            line -> {
              String[] fields = line.split("\t", -1);
              if (fields.length != 7 || fields[2].isBlank() || fields[3].isBlank()) {
                throw new IllegalArgumentException("Invalid simulator registry row");
              }
              var device =
                  new Device(
                      UUID.fromString(fields[0]), UUID.fromString(fields[1]), fields[2], fields[3]);
              var sensor = UUID.fromString(fields[4]);
              if (!keys.add(List.of(device.tenantId(), sensor))) {
                throw new IllegalArgumentException("Repeated simulator sensor assignment");
              }
              return new SensorStream(
                  device, sensor, SensorType.fromCode(fields[5]), Integer.parseInt(fields[6]));
            })
        .toList();
  }

  record Device(UUID tenantId, UUID deviceId, String clientId, String principal) {
    String topicPrefix() {
      return "iot/v1/" + tenantId + "/" + deviceId + "/";
    }
  }
}
