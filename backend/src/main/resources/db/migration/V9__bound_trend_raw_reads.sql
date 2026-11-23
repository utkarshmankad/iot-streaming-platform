CREATE OR REPLACE FUNCTION telemetry.read_trend_buckets(
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

    -- Aggregate each sensor and uncovered interval separately so the time and identity
    -- predicates can use the same index scan, even with a generic prepared plan.
    SELECT s.sensor_id, q.bucket_start, q.samples, q.total, q.minimum, q.maximum, q.last_observed_at
    FROM selection s
    CROSS JOIN raw_ranges r
    CROSS JOIN LATERAL (
        SELECT public.time_bucket(interval '15 minutes', t.observed_at) AS bucket_start,
               count(*) AS samples, sum(t.value) AS total, min(t.value) AS minimum,
               max(t.value) AS maximum, max(t.observed_at) AS last_observed_at
        FROM telemetry.readings t
        WHERE t.tenant_id = s.tenant_id AND t.sensor_id = s.sensor_id
          AND t.observed_at >= r.lower_bound AND t.observed_at < r.upper_bound
        GROUP BY public.time_bucket(interval '15 minutes', t.observed_at)
    ) q
    WHERE r.lower_bound < r.upper_bound
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
