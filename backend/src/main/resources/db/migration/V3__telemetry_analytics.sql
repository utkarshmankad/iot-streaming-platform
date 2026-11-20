CREATE MATERIALIZED VIEW telemetry.sensor_15m
WITH (timescaledb.continuous, timescaledb.materialized_only = true, timescaledb.create_group_indexes = false)
AS
SELECT tenant_id, sensor_id, public.time_bucket(interval '15 minutes', observed_at) AS bucket_start,
       count(*) AS samples, sum(value) AS total, min(value) AS minimum, max(value) AS maximum,
       max(observed_at) AS last_observed_at
FROM telemetry.readings
GROUP BY tenant_id, sensor_id, public.time_bucket(interval '15 minutes', observed_at)
WITH NO DATA;

CREATE INDEX sensor_15m_tenant_sensor_bucket
    ON telemetry.sensor_15m (tenant_id, sensor_id, bucket_start DESC);

-- Publish the contiguous coverage only after a refresh returns successfully.
CREATE TABLE telemetry.analytics_coverage (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    covered_from timestamptz,
    covered_until timestamptz,
    recent_refreshed_at timestamptz,
    CHECK ((covered_from IS NULL AND covered_until IS NULL)
           OR (covered_from IS NOT NULL AND covered_until IS NOT NULL AND covered_from < covered_until))
);
INSERT INTO telemetry.analytics_coverage (singleton) VALUES (true);

CREATE SEQUENCE telemetry.repair_revision;
CREATE TABLE telemetry.analytics_repairs (
    tenant_id uuid NOT NULL,
    sensor_id uuid NOT NULL,
    bucket_start timestamptz NOT NULL,
    revision bigint NOT NULL DEFAULT nextval('telemetry.repair_revision'),
    PRIMARY KEY (tenant_id, sensor_id, bucket_start),
    FOREIGN KEY (tenant_id, sensor_id) REFERENCES registry.sensors
);
CREATE INDEX analytics_repairs_by_bucket ON telemetry.analytics_repairs (bucket_start, revision);
ALTER TABLE telemetry.analytics_repairs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON telemetry.analytics_repairs TO iot_app
    USING (tenant_id = nullif(current_setting('iot.tenant_id', true), '')::uuid);

CREATE FUNCTION telemetry.queue_late_reading()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog
AS $$
BEGIN
    INSERT INTO telemetry.analytics_repairs (tenant_id, sensor_id, bucket_start)
    VALUES (NEW.tenant_id, NEW.sensor_id, public.time_bucket(interval '15 minutes', NEW.observed_at))
    ON CONFLICT (tenant_id, sensor_id, bucket_start)
    DO UPDATE SET revision = EXCLUDED.revision;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION telemetry.queue_late_reading() FROM PUBLIC;
CREATE TRIGGER queue_analytics_repair AFTER INSERT ON telemetry.readings
FOR EACH ROW WHEN (
    -- One overlapping quarter protects arrivals at the rolling policy boundary.
    NEW.observed_at < public.time_bucket(interval '15 minutes', statement_timestamp()) - interval '2865 minutes'
)
EXECUTE FUNCTION telemetry.queue_late_reading();

CREATE PROCEDURE telemetry.refresh_recent_analytics(job_id integer, config jsonb)
LANGUAGE plpgsql
AS $$
DECLARE
    recent_end timestamptz := public.time_bucket(interval '15 minutes', statement_timestamp());
    recent_start timestamptz := recent_end - interval '48 hours';
    stored_from timestamptz;
    stored_until timestamptz;
    fetch_from timestamptz;
    backfill_from timestamptz;
BEGIN
    SELECT covered_from, covered_until INTO stored_from, stored_until
    FROM telemetry.analytics_coverage;

    -- Bridge a gap after job downtime before advancing the upper edge. A clock step backwards
    -- may refresh earlier buckets without moving either published edge inwards.
    fetch_from := least(coalesce(stored_until, recent_start), recent_start);
    CALL public.refresh_continuous_aggregate('telemetry.sensor_15m', fetch_from, recent_end);
    UPDATE telemetry.analytics_coverage
    SET covered_from = least(coalesce(covered_from, fetch_from), fetch_from),
        covered_until = greatest(coalesce(covered_until, recent_end), recent_end),
        recent_refreshed_at = clock_timestamp();
    COMMIT;

    -- Give pre-existing history contiguous coverage a day at a time. Readers use raw data
    -- below the lower edge while this proceeds.
    stored_from := least(coalesce(stored_from, fetch_from), fetch_from);
    SELECT greatest(stored_from - interval '24 hours', recent_end - interval '720 hours',
                    coalesce(min(range_start), stored_from))
    INTO backfill_from
    FROM timescaledb_information.chunks
    WHERE hypertable_schema = 'telemetry' AND hypertable_name = 'readings';

    IF backfill_from < stored_from THEN
        CALL public.refresh_continuous_aggregate('telemetry.sensor_15m', backfill_from, stored_from);
        UPDATE telemetry.analytics_coverage SET covered_from = least(covered_from, backfill_from);
    END IF;
END;
$$;

CREATE PROCEDURE telemetry.repair_analytics(job_id integer, config jsonb)
LANGUAGE plpgsql
AS $$
DECLARE
    affected timestamptz;
    revisions bigint[];
BEGIN
    FOR affected IN
        SELECT DISTINCT bucket_start FROM telemetry.analytics_repairs ORDER BY bucket_start LIMIT 12
    LOOP
        SELECT array_agg(revision) INTO revisions
        FROM telemetry.analytics_repairs WHERE bucket_start = affected;

        IF revisions IS NOT NULL THEN
            CALL public.refresh_continuous_aggregate('telemetry.sensor_15m', affected,
                                                      affected + interval '15 minutes', force => true);
            -- A late arrival during the refresh carries a new token and remains queued.
            DELETE FROM telemetry.analytics_repairs
            WHERE bucket_start = affected AND revision = ANY (revisions);
            COMMIT;
        END IF;
    END LOOP;
END;
$$;

REVOKE ALL ON PROCEDURE telemetry.refresh_recent_analytics(integer, jsonb) FROM PUBLIC;
REVOKE ALL ON PROCEDURE telemetry.repair_analytics(integer, jsonb) FROM PUBLIC;
SELECT public.add_job('telemetry.refresh_recent_analytics', interval '1 minute',
                      initial_start => clock_timestamp() + interval '1 minute');
SELECT public.add_job('telemetry.repair_analytics', interval '1 minute',
                      initial_start => clock_timestamp() + interval '30 seconds');

-- Scope is bound by the backend inside its authorized transaction. The application does not
-- receive SELECT grants on the raw, materialized, coverage, or Timescale internal relations.
CREATE FUNCTION telemetry.read_trend_buckets(
    sensors uuid[], first_at timestamptz, end_at timestamptz, stride interval, as_of timestamptz
)
RETURNS TABLE (
    sensor_id uuid, bucket_start timestamptz, bucket_end timestamptz, samples bigint,
    minimum double precision, maximum double precision, mean double precision,
    partial boolean, last_observed_at timestamptz, covered_until timestamptz,
    recent_refreshed_at timestamptz, repair_pending boolean
)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog SET timezone = 'UTC'
AS $$
WITH selection AS MATERIALIZED (
    SELECT s.tenant_id, s.sensor_id
    FROM registry.sensors s
    WHERE s.tenant_id = nullif(current_setting('iot.tenant_id', true), '')::uuid
      AND s.sensor_id = ANY (sensors) AND cardinality(sensors) BETWEEN 1 AND 10
      AND first_at < end_at AND end_at - first_at <= interval '720 hours'
      AND stride IN (interval '15 minutes', interval '1 hour', interval '1 day', interval '1 week')
      AND public.time_bucket(stride, first_at) = first_at
      AND public.time_bucket(stride, end_at) = end_at
),
coverage AS MATERIALIZED (
    SELECT c.covered_from, c.covered_until, c.recent_refreshed_at
    FROM telemetry.analytics_coverage c
),
raw_ranges AS (
    SELECT first_at AS lower_bound, least(end_at, coalesce(c.covered_from, end_at)) AS upper_bound
    FROM coverage c
    UNION ALL
    SELECT greatest(first_at, c.covered_until), end_at
    FROM coverage c WHERE c.covered_until IS NOT NULL
),
quarter_states AS (
    SELECT m.sensor_id, m.bucket_start, m.samples, m.total, m.minimum, m.maximum, m.last_observed_at
    FROM telemetry.sensor_15m m
    JOIN selection s ON s.tenant_id = m.tenant_id AND s.sensor_id = m.sensor_id
    CROSS JOIN coverage c
    WHERE m.bucket_start >= greatest(first_at, c.covered_from)
      AND m.bucket_start < least(end_at, c.covered_until) AND c.covered_from IS NOT NULL

    UNION ALL

    SELECT t.sensor_id, public.time_bucket(interval '15 minutes', t.observed_at),
           count(*), sum(t.value), min(t.value), max(t.value), max(t.observed_at)
    FROM telemetry.readings t
    JOIN selection s ON s.tenant_id = t.tenant_id AND s.sensor_id = t.sensor_id
    JOIN raw_ranges r ON t.observed_at >= r.lower_bound AND t.observed_at < r.upper_bound
    GROUP BY t.sensor_id, public.time_bucket(interval '15 minutes', t.observed_at)
),
rolled AS (
    SELECT q.sensor_id, public.time_bucket(stride, q.bucket_start) AS starts_at,
           sum(q.samples)::bigint AS samples, min(q.minimum) AS minimum, max(q.maximum) AS maximum,
           sum(q.total) / sum(q.samples) AS mean, max(q.last_observed_at) AS last_observed_at
    FROM quarter_states q GROUP BY q.sensor_id, public.time_bucket(stride, q.bucket_start)
),
pending AS (
    SELECT DISTINCT p.sensor_id, public.time_bucket(stride, p.bucket_start) AS starts_at
    FROM telemetry.analytics_repairs p
    JOIN selection s ON s.tenant_id = p.tenant_id AND s.sensor_id = p.sensor_id
    WHERE p.bucket_start >= first_at AND p.bucket_start < end_at
)
SELECT s.sensor_id, b.starts_at, b.starts_at + stride, coalesce(a.samples, 0::bigint),
       a.minimum, a.maximum, a.mean, b.starts_at <= as_of AND as_of < b.starts_at + stride,
       a.last_observed_at, c.covered_until, c.recent_refreshed_at, p.sensor_id IS NOT NULL
FROM selection s
CROSS JOIN coverage c
CROSS JOIN LATERAL generate_series(first_at, end_at - stride, stride) b(starts_at)
LEFT JOIN rolled a ON a.sensor_id = s.sensor_id AND a.starts_at = b.starts_at
LEFT JOIN pending p ON p.sensor_id = s.sensor_id AND p.starts_at = b.starts_at
ORDER BY s.sensor_id, b.starts_at;
$$;

REVOKE ALL ON FUNCTION telemetry.read_trend_buckets(uuid[], timestamptz, timestamptz, interval, timestamptz)
FROM PUBLIC;
GRANT EXECUTE ON FUNCTION telemetry.read_trend_buckets(uuid[], timestamptz, timestamptz, interval, timestamptz)
TO iot_app;
