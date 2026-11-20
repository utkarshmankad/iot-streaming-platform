CREATE TYPE registry.lifecycle AS ENUM ('active', 'disabled', 'retired');

CREATE TABLE registry.tenants (
    tenant_id uuid PRIMARY KEY,
    slug text NOT NULL UNIQUE,
    display_name text NOT NULL,
    status registry.lifecycle NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE TABLE registry.operators (
    operator_id uuid PRIMARY KEY,
    username text NOT NULL UNIQUE,
    status registry.lifecycle NOT NULL DEFAULT 'active'
);

CREATE TABLE registry.operator_memberships (
    tenant_id uuid NOT NULL REFERENCES registry.tenants,
    operator_id uuid NOT NULL REFERENCES registry.operators,
    role text NOT NULL CHECK (role IN ('viewer', 'operator')),
    PRIMARY KEY (tenant_id, operator_id)
);

CREATE TABLE registry.gateways (
    tenant_id uuid NOT NULL REFERENCES registry.tenants,
    gateway_id uuid NOT NULL,
    name text NOT NULL,
    credential_ref text NOT NULL UNIQUE CHECK (credential_ref ~ '^[a-z0-9_-]+$'),
    status registry.lifecycle NOT NULL DEFAULT 'active',
    PRIMARY KEY (tenant_id, gateway_id)
);

-- Only the backend's internal service-auth lookup reads this global index.
CREATE TABLE registry.gateway_credentials (
    token_digest bytea PRIMARY KEY CHECK (octet_length(token_digest) = 32),
    tenant_id uuid NOT NULL,
    gateway_id uuid NOT NULL,
    UNIQUE (tenant_id, gateway_id),
    FOREIGN KEY (tenant_id, gateway_id) REFERENCES registry.gateways
);

CREATE TABLE registry.devices (
    tenant_id uuid NOT NULL,
    device_id uuid NOT NULL,
    gateway_id uuid NOT NULL,
    mqtt_principal_ref text NOT NULL UNIQUE CHECK (mqtt_principal_ref ~ '^[a-z0-9_-]+$'),
    broker_client_id text NOT NULL UNIQUE,
    label text NOT NULL,
    status registry.lifecycle NOT NULL DEFAULT 'active',
    PRIMARY KEY (tenant_id, device_id),
    FOREIGN KEY (tenant_id, gateway_id) REFERENCES registry.gateways
);
CREATE INDEX devices_by_gateway ON registry.devices (tenant_id, gateway_id);

CREATE TABLE registry.sensor_types (
    type_code text PRIMARY KEY,
    canonical_unit text NOT NULL,
    description text NOT NULL,
    default_sampling_period_ms integer NOT NULL CHECK (default_sampling_period_ms > 0)
);

CREATE TABLE registry.sensor_schema_versions (
    type_code text NOT NULL REFERENCES registry.sensor_types,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    protobuf_contract text NOT NULL,
    validator_revision text NOT NULL,
    min_value double precision NOT NULL,
    max_value double precision NOT NULL,
    accepted boolean NOT NULL DEFAULT true,
    PRIMARY KEY (type_code, schema_version),
    CHECK (min_value > '-Infinity'::double precision AND max_value < 'Infinity'::double precision
        AND min_value <= max_value)
);

CREATE TABLE registry.sensors (
    tenant_id uuid NOT NULL,
    sensor_id uuid NOT NULL,
    device_id uuid NOT NULL,
    type_code text NOT NULL REFERENCES registry.sensor_types,
    display_name text NOT NULL,
    sampling_period_ms integer NOT NULL DEFAULT 1000 CHECK (sampling_period_ms > 0),
    status registry.lifecycle NOT NULL DEFAULT 'active',
    PRIMARY KEY (tenant_id, sensor_id),
    FOREIGN KEY (tenant_id, device_id) REFERENCES registry.devices
);
CREATE INDEX sensors_by_device ON registry.sensors (tenant_id, device_id);

CREATE TABLE registry.sensor_state (
    tenant_id uuid NOT NULL,
    sensor_id uuid NOT NULL,
    desired_enabled boolean NOT NULL DEFAULT true,
    confirmed_enabled boolean,
    applied_version bigint NOT NULL DEFAULT 0 CHECK (applied_version >= 0),
    last_heartbeat_at timestamptz,
    last_observed_at timestamptz,
    PRIMARY KEY (tenant_id, sensor_id),
    FOREIGN KEY (tenant_id, sensor_id) REFERENCES registry.sensors
);

DO $$
DECLARE relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY['tenants','operator_memberships','gateways','devices','sensors','sensor_state']
    LOOP
        EXECUTE format('ALTER TABLE registry.%I ENABLE ROW LEVEL SECURITY', relation_name);
        EXECUTE format(
            'CREATE POLICY tenant_scope ON registry.%I TO iot_app USING
             (tenant_id = nullif(current_setting(''iot.tenant_id'', true), '''')::uuid)',
            relation_name);
    END LOOP;
END $$;

GRANT USAGE ON SCHEMA registry TO iot_app;
GRANT SELECT ON registry.tenants, registry.operator_memberships, registry.gateways,
    registry.devices, registry.sensors, registry.sensor_state, registry.sensor_types,
    registry.sensor_schema_versions, registry.gateway_credentials TO iot_app;
GRANT SELECT (operator_id, status) ON registry.operators TO iot_app;

INSERT INTO registry.sensor_types VALUES
    ('temperature', '°C', 'Temperature', 1000),
    ('humidity', '%', 'Relative humidity', 1000),
    ('pressure', 'hPa', 'Atmospheric pressure', 1000),
    ('pm25', 'µg/m³', 'Particulate matter PM2.5', 1000),
    ('light', 'lux', 'Ambient light', 1000),
    ('oxygen', '% vol', 'Ambient oxygen concentration', 1000);

INSERT INTO registry.sensor_schema_versions
    (type_code, schema_version, protobuf_contract, validator_revision, min_value, max_value)
VALUES
    ('temperature', 1, 'iot.telemetry.v1.TelemetryEvent', '1', -100, 100),
    ('humidity', 1, 'iot.telemetry.v1.TelemetryEvent', '1', 0, 100),
    ('pressure', 1, 'iot.telemetry.v1.TelemetryEvent', '1', 0, 2000),
    ('pm25', 1, 'iot.telemetry.v1.TelemetryEvent', '1', 0, 100000),
    ('light', 1, 'iot.telemetry.v1.TelemetryEvent', '1', 0, 1000000),
    ('oxygen', 1, 'iot.telemetry.v1.TelemetryEvent', '1', 0, 100);
