CREATE TABLE telemetry.event_receipts (
    tenant_id uuid NOT NULL,
    sensor_id uuid NOT NULL,
    event_id uuid NOT NULL,
    fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
    PRIMARY KEY (tenant_id, sensor_id, event_id),
    FOREIGN KEY (tenant_id, sensor_id) REFERENCES registry.sensors
);

CREATE TABLE telemetry.readings (
    tenant_id uuid NOT NULL,
    sensor_id uuid NOT NULL,
    event_id uuid NOT NULL,
    observed_at timestamptz NOT NULL,
    gateway_received_at timestamptz NOT NULL,
    backend_received_at timestamptz NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    value double precision NOT NULL CHECK (value > '-Infinity'::double precision
        AND value < 'Infinity'::double precision),
    payload_bytes integer NOT NULL CHECK (payload_bytes BETWEEN 1 AND 4096),
    FOREIGN KEY (tenant_id, sensor_id, event_id) REFERENCES telemetry.event_receipts
);

SELECT public.create_hypertable('telemetry.readings',
    public.by_range('observed_at', interval '1 day'), create_default_indexes => false);

CREATE UNIQUE INDEX readings_identity ON telemetry.readings
    (tenant_id, sensor_id, observed_at DESC, event_id);

ALTER TABLE telemetry.event_receipts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON telemetry.event_receipts TO iot_app
    USING (tenant_id = nullif(current_setting('iot.tenant_id', true), '')::uuid);

-- Continuous aggregates require an unfiltered source hypertable. The runtime role can only
-- insert here in this milestone. The application supplies its authenticated tenant on every write.
GRANT USAGE ON SCHEMA telemetry TO iot_app;
GRANT SELECT, INSERT ON telemetry.event_receipts TO iot_app;
GRANT INSERT ON telemetry.readings TO iot_app;
