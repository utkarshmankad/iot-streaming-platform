package iot.platform.gateway.ingest;

import iot.platform.registry.proto.RegistrySnapshot;
import iot.platform.telemetry.SensorType;
import iot.platform.telemetry.TelemetryValidation;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class IngressRegistry {
  private RegistrySnapshot snapshot;
  private Map<String, Assignment> sensors = Map.of();

  Assignment resolve(RegistrySnapshot verified, String topic) {
    if (snapshot != verified) {
      sensors = index(verified);
      snapshot = verified;
    }
    return sensors.get(topic);
  }

  private static Map<String, Assignment> index(RegistrySnapshot verified) {
    if (verified == null) {
      return Map.of();
    }
    var assignments = new HashMap<String, Assignment>();
    String tenantPrefix = "iot/v1/" + verified.getTenantId() + "/";
    for (var device : verified.getDevicesList()) {
      for (var sensor : device.getSensorsList()) {
        var supported =
            sensor.getAcceptedSchemasList().stream()
                .filter(
                    schema ->
                        schema.getVersion() == TelemetryValidation.VERSION
                            && "iot.telemetry.v1.TelemetryEvent"
                                .equals(schema.getProtobufContract())
                            && "1".equals(schema.getValidatorRevision()))
                .findFirst();
        if (supported.isEmpty()) {
          continue;
        }
        try {
          var schema = supported.orElseThrow();
          var assignment =
              new Assignment(
                  device.getDeviceId(),
                  UUID.fromString(sensor.getSensorId()),
                  SensorType.fromCode(sensor.getTypeCode()),
                  schema.getMinValue(),
                  schema.getMaxValue());
          assignments.put(
              tenantPrefix + device.getDeviceId() + "/" + sensor.getSensorId() + "/telemetry",
              assignment);
        } catch (IllegalArgumentException unsupported) {
          // An unknown sensor type stays ineligible until the gateway supports its validator.
        }
      }
    }
    return assignments;
  }

  record Assignment(String device, UUID sensor, SensorType type, double minimum, double maximum) {}
}
