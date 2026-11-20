INSERT INTO registry.tenants (tenant_id, slug, display_name)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'demo-a', 'Demo A'),
       ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'demo-b', 'Demo B')
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO registry.operators (operator_id, username)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-000000000001', 'operator-a'),
       ('bbbbbbbb-bbbb-4bbb-8bbb-000000000001', 'operator-b')
ON CONFLICT (operator_id) DO NOTHING;

INSERT INTO registry.operator_memberships
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'aaaaaaaa-aaaa-4aaa-8aaa-000000000001', 'operator'),
       ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'bbbbbbbb-bbbb-4bbb-8bbb-000000000001', 'viewer')
ON CONFLICT (tenant_id, operator_id) DO NOTHING;

INSERT INTO registry.gateways (tenant_id, gateway_id, name, credential_ref)
SELECT tenant_id, '00000000-0000-4000-8000-000000000001', 'Demo gateway', 'gateway-' || right(slug, 1)
FROM registry.tenants WHERE slug IN ('demo-a','demo-b')
ON CONFLICT (tenant_id, gateway_id) DO NOTHING;

INSERT INTO registry.devices (tenant_id, device_id, gateway_id, mqtt_principal_ref, broker_client_id, label)
SELECT tenant_id, '00000000-0000-4000-8000-000000000002', gateway_id,
    'device-' || right(credential_ref, 1), 'iot-demo-' || right(credential_ref, 1), 'Demo device'
FROM registry.gateways WHERE credential_ref IN ('gateway-a', 'gateway-b')
ON CONFLICT (tenant_id, device_id) DO NOTHING;

INSERT INTO registry.sensors (tenant_id, sensor_id, device_id, type_code, display_name)
SELECT device.tenant_id,
    ('00000000-0000-4000-8000-' || lpad((slot.number * 6 + fixture.ordinal)::text, 12, '0'))::uuid,
    device.device_id, fixture.type_code, kind.description
FROM registry.devices device
CROSS JOIN generate_series(0, current_setting('iot.demo_streams_per_type')::integer - 1) slot(number)
CROSS JOIN (VALUES
    (1, 'temperature'),
    (2, 'humidity'),
    (3, 'pressure'),
    (4, 'pm25'),
    (5, 'light'),
    (6, 'oxygen')
) fixture(ordinal, type_code)
JOIN registry.sensor_types kind ON kind.type_code = fixture.type_code
WHERE device.mqtt_principal_ref IN ('device-a','device-b')
ON CONFLICT (tenant_id, sensor_id) DO NOTHING;

INSERT INTO registry.sensor_state (tenant_id, sensor_id)
SELECT sensor.tenant_id, sensor.sensor_id
FROM registry.sensors sensor JOIN registry.devices device USING (tenant_id, device_id)
WHERE device.mqtt_principal_ref IN ('device-a', 'device-b')
ON CONFLICT (tenant_id, sensor_id) DO NOTHING;
