CREATE FUNCTION registry.browser_memberships(login_name text)
RETURNS TABLE (operator_id uuid, tenant_id uuid, display_name text, role text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog
AS $$
    SELECT o.operator_id, t.tenant_id, t.display_name, m.role
    FROM registry.operators o
    JOIN registry.operator_memberships m ON m.operator_id = o.operator_id
    JOIN registry.tenants t ON t.tenant_id = m.tenant_id
    WHERE o.username = login_name AND o.status = 'active' AND t.status = 'active'
    ORDER BY t.tenant_id;
$$;
REVOKE ALL ON FUNCTION registry.browser_memberships(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION registry.browser_memberships(text) TO iot_app;

CREATE FUNCTION telemetry.browser_last_observations(sensors uuid[])
RETURNS TABLE (sensor_id uuid, observed_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog
AS $$
    SELECT s.sensor_id, latest.observed_at
    FROM registry.sensors s
    LEFT JOIN LATERAL (
        SELECT r.observed_at FROM telemetry.readings r
        WHERE r.tenant_id = s.tenant_id AND r.sensor_id = s.sensor_id
        ORDER BY r.observed_at DESC LIMIT 1
    ) latest ON true
    WHERE s.tenant_id = nullif(current_setting('iot.tenant_id', true), '')::uuid
      AND s.sensor_id = ANY (sensors) AND cardinality(sensors) <= 200;
$$;
REVOKE ALL ON FUNCTION telemetry.browser_last_observations(uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION telemetry.browser_last_observations(uuid[]) TO iot_app;
