\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE observed_state (
    tenant_id uuid NOT NULL,
    sensor_id uuid NOT NULL,
    version bigint NOT NULL,
    enabled boolean NOT NULL,
    PRIMARY KEY (tenant_id, sensor_id)
) ON COMMIT DROP;
\copy observed_state FROM '/tmp/iot-recovery-state.csv' WITH (FORMAT csv)

-- The fresh broker has no old sessions. Begin a new version range above every
-- command and both the restored registry and simulator state.
CREATE TEMP TABLE new_generation AS
SELECT greatest(
    coalesce((SELECT max(version) FROM observed_state),0),
    coalesce((SELECT max(version) FROM registry.commands),0),
    coalesce((SELECT max(command_version) FROM registry.sensor_state),0),
    coalesce((SELECT max(applied_version) FROM registry.sensor_state),0)
) + 1 AS version_floor;
INSERT INTO registry.restore_generations (generation_id,version_floor)
SELECT :'generation'::uuid,version_floor FROM new_generation;
UPDATE registry.commands
SET status = CASE WHEN expires_at <= clock_timestamp() THEN 'expired' ELSE 'superseded' END,
    resolved_at = least(expires_at,clock_timestamp()),
    reason_code = CASE WHEN expires_at <= clock_timestamp() THEN 'COMMAND_EXPIRED' ELSE 'RESTORE_GENERATION' END
WHERE status='pending';
INSERT INTO registry.sensor_state AS st
    (tenant_id,sensor_id,desired_enabled,confirmed_enabled,applied_version,command_version)
SELECT s.tenant_id,s.sensor_id,coalesce(o.enabled,true),coalesce(o.enabled,true),
       g.version_floor,g.version_floor
FROM registry.sensors s
LEFT JOIN observed_state o USING (tenant_id,sensor_id)
CROSS JOIN new_generation g
WHERE s.status='active'
ON CONFLICT (tenant_id,sensor_id) DO UPDATE
    SET desired_enabled=excluded.desired_enabled, confirmed_enabled=excluded.confirmed_enabled,
        applied_version=excluded.applied_version, command_version=excluded.command_version,
        last_heartbeat_at=NULL, last_heartbeat_source_at=NULL;
COMMIT;
