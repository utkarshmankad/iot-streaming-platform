CREATE TABLE registry.restore_generations (
    generation_id uuid PRIMARY KEY,
    version_floor bigint NOT NULL CHECK (version_floor > 0),
    reconciled_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON registry.restore_generations FROM PUBLIC;

CREATE FUNCTION telemetry.wal_pending_since()
RETURNS double precision LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog
RETURN CASE WHEN current_setting('archive_mode') = 'off' THEN 0::double precision
    ELSE coalesce((
        SELECT EXTRACT(EPOCH FROM min(modification))::double precision
        FROM pg_catalog.pg_ls_archive_statusdir() WHERE name LIKE '%.ready'
    ), 0::double precision)
END;
REVOKE ALL ON FUNCTION telemetry.wal_pending_since() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION telemetry.wal_pending_since() TO iot_app;
