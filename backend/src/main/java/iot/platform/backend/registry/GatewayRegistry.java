package iot.platform.backend.registry;

import iot.platform.registry.proto.RegistryDevice;
import iot.platform.registry.proto.RegistrySchema;
import iot.platform.registry.proto.RegistrySensor;
import iot.platform.registry.proto.RegistrySnapshot;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("registry")
class GatewayRegistry {
  private final JdbcClient jdbc;
  private final GatewayAccess access;

  GatewayRegistry(JdbcClient jdbc, GatewayAccess access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  @Transactional(readOnly = true)
  public Optional<RegistrySnapshot> snapshot(String token) {
    return access.authorize(token).map(this::loadSnapshot);
  }

  private RegistrySnapshot loadSnapshot(GatewayAccess.Assignment assignment) {
    var snapshot =
        RegistrySnapshot.newBuilder()
            .setFormatVersion(1)
            .setTenantId(assignment.tenant().toString())
            .setGatewayId(assignment.gateway().toString());
    var devices = new LinkedHashMap<String, RegistryDevice.Builder>();
    var sensors = new LinkedHashMap<String, RegistrySensor.Builder>();
    jdbc.sql(
            """
        SELECT d.device_id, d.broker_client_id, s.sensor_id, s.type_code,
               t.canonical_unit, s.sampling_period_ms, v.schema_version, v.protobuf_contract,
               v.validator_revision, v.min_value, v.max_value
        FROM registry.devices d
        LEFT JOIN registry.sensors s
            ON s.tenant_id = d.tenant_id AND s.device_id = d.device_id AND s.status = 'active'
        LEFT JOIN registry.sensor_types t ON t.type_code = s.type_code
        LEFT JOIN registry.sensor_schema_versions v ON v.type_code = t.type_code AND v.accepted
        WHERE d.tenant_id = ? AND d.gateway_id = ? AND d.status = 'active'
        ORDER BY d.device_id, s.sensor_id, v.schema_version
        """)
        .params(assignment.tenant(), assignment.gateway())
        .query(
            row -> {
              while (row.next()) {
                String deviceId = row.getString("device_id");
                var device = devices.get(deviceId);
                if (device == null) {
                  device =
                      snapshot
                          .addDevicesBuilder()
                          .setDeviceId(deviceId)
                          .setBrokerClientId(row.getString("broker_client_id"));
                  devices.put(deviceId, device);
                }
                String sensorId = row.getString("sensor_id");
                if (sensorId == null) {
                  continue;
                }
                var sensor = sensors.get(sensorId);
                if (sensor == null) {
                  sensor =
                      device
                          .addSensorsBuilder()
                          .setSensorId(sensorId)
                          .setTypeCode(row.getString("type_code"))
                          .setUnit(row.getString("canonical_unit"))
                          .setSamplingPeriodMs(row.getInt("sampling_period_ms"));
                  sensors.put(sensorId, sensor);
                }
                int version = row.getInt("schema_version");
                if (!row.wasNull()) {
                  sensor.addAcceptedSchemas(
                      RegistrySchema.newBuilder()
                          .setVersion(version)
                          .setProtobufContract(row.getString("protobuf_contract"))
                          .setValidatorRevision(row.getString("validator_revision"))
                          .setMinValue(row.getDouble("min_value"))
                          .setMaxValue(row.getDouble("max_value")));
                }
              }
              return snapshot;
            });
    return snapshot.build();
  }
}
