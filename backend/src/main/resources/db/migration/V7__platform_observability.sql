-- Expose fleet totals and timestamps without giving the runtime role access to other tenants' rows.
CREATE FUNCTION telemetry.platform_health()
RETURNS TABLE (
    enabled_sensors bigint, stale_sensors bigint, offline_sensors bigint,
    ready_commands bigint, oldest_command_seconds double precision,
    applied_commands bigint, expired_commands bigint, failed_commands bigint, superseded_commands bigint,
    aggregate_refresh_seconds double precision, wal_archiving integer,
    last_wal_archive_seconds double precision, wal_archive_failures bigint
)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog
AS $$
    WITH sensors AS (
        SELECT s.tenant_id, s.sensor_id, st.last_heartbeat_at
        FROM registry.sensors s
        JOIN registry.devices d USING (tenant_id,device_id)
        JOIN registry.gateways g USING (tenant_id,gateway_id)
        LEFT JOIN registry.sensor_state st USING (tenant_id,sensor_id)
        WHERE s.status='active' AND d.status='active' AND g.status='active'
          AND st.confirmed_enabled IS DISTINCT FROM false
    ), fleet AS (
        SELECT count(*) AS enabled,
               count(*) FILTER (WHERE NOT EXISTS (
                   SELECT 1 FROM telemetry.readings r
                   WHERE r.tenant_id=s.tenant_id AND r.sensor_id=s.sensor_id
                     AND r.observed_at >= now() - interval '10 seconds'
                     AND r.observed_at <= now()
               )) AS stale,
               count(*) FILTER (WHERE last_heartbeat_at IS NULL
                   OR last_heartbeat_at <= now() - interval '15 seconds') AS offline
        FROM sensors s
    ), commands AS (
        SELECT count(*) FILTER (WHERE status='pending' AND expires_at > now()) AS pending,
               coalesce(EXTRACT(EPOCH FROM now() - min(created_at) FILTER (
                   WHERE status='pending' AND expires_at > now())),0)::double precision AS oldest,
               count(*) FILTER (WHERE status='applied') AS applied,
               count(*) FILTER (WHERE status='expired' OR status='pending' AND expires_at <= now()) AS expired,
               count(*) FILTER (WHERE status='failed') AS failed,
               count(*) FILTER (WHERE status='superseded') AS superseded
        FROM registry.commands
    )
    SELECT fleet.*, commands.*,
           coalesce(EXTRACT(EPOCH FROM c.recent_refreshed_at),0)::double precision,
           (current_setting('archive_mode') <> 'off')::integer,
           coalesce(EXTRACT(EPOCH FROM a.last_archived_time),0)::double precision, a.failed_count
    FROM fleet CROSS JOIN commands CROSS JOIN telemetry.analytics_coverage c
    CROSS JOIN pg_catalog.pg_stat_archiver a;
$$;
REVOKE ALL ON FUNCTION telemetry.platform_health() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION telemetry.platform_health() TO iot_app;
