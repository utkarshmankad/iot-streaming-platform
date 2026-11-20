ALTER TABLE registry.sensor_state
    ADD COLUMN command_version bigint NOT NULL DEFAULT 0 CHECK (command_version >= 0);
UPDATE registry.sensor_state SET command_version = applied_version;

-- Pending command rows are the durable outbox. Audit rows survive delivery and expiry.
CREATE TABLE registry.commands (
    tenant_id uuid NOT NULL,
    command_id uuid NOT NULL,
    sensor_id uuid NOT NULL,
    device_id uuid NOT NULL,
    gateway_id uuid NOT NULL,
    operator_id uuid NOT NULL REFERENCES registry.operators,
    idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    version bigint NOT NULL CHECK (version > 0),
    requested_enabled boolean NOT NULL,
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'applied', 'failed', 'expired', 'superseded')),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    resolved_at timestamptz,
    reason_code text,
    ack_received_at timestamptz,
    applied_at timestamptz,
    actual_enabled boolean,
    PRIMARY KEY (tenant_id, command_id),
    UNIQUE (tenant_id, sensor_id, version),
    UNIQUE (tenant_id, operator_id, sensor_id, idempotency_key),
    FOREIGN KEY (tenant_id, sensor_id) REFERENCES registry.sensors,
    FOREIGN KEY (tenant_id, device_id) REFERENCES registry.devices,
    FOREIGN KEY (tenant_id, gateway_id) REFERENCES registry.gateways
);
CREATE INDEX commands_pending_by_gateway ON registry.commands
    (tenant_id, gateway_id, expires_at, command_id) WHERE status = 'pending';
CREATE INDEX commands_pending_by_sensor ON registry.commands
    (tenant_id, sensor_id) WHERE status = 'pending';

ALTER TABLE registry.commands ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON registry.commands TO iot_app
    USING (tenant_id = nullif(current_setting('iot.tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON registry.commands TO iot_app;
GRANT UPDATE (status, resolved_at, reason_code, ack_received_at, applied_at, actual_enabled)
    ON registry.commands TO iot_app;
GRANT INSERT (tenant_id, sensor_id) ON registry.sensor_state TO iot_app;
GRANT UPDATE (desired_enabled, confirmed_enabled, applied_version, command_version)
    ON registry.sensor_state TO iot_app;
