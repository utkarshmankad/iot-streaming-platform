ALTER TABLE registry.sensor_state ADD COLUMN last_heartbeat_source_at timestamptz;
UPDATE registry.sensor_state SET last_heartbeat_source_at=last_heartbeat_at;
GRANT INSERT (last_heartbeat_at,last_heartbeat_source_at),
    UPDATE (last_heartbeat_at,last_heartbeat_source_at) ON registry.sensor_state TO iot_app;
