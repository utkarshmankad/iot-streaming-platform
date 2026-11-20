#!/usr/bin/env bash
set -euo pipefail

url="http://127.0.0.1:${IOT_HTTP_PORT:-18080}"
curl --fail --silent --show-error "$url/healthz" | grep -Fx ok
curl --fail --silent --show-error "$url/" | grep -F '<title>Environmental IoT</title>'
api_response="$(curl --silent --show-error --header 'Accept: application/json' -o /dev/null \
  -w '%{http_code} %{content_type}' "$url/api/v1/sensors")"
[[ "$api_response" =~ ^(401|404)\ application/ ]]

# Both gateway principals must be able to read and cache their scoped Protobuf snapshots.
for attempt in 1 2 3 4 5 6 7 8 9; do
  if docker compose --env-file .env.test -p iot-platform-ci --profile test exec -T gateway \
    sh -ec 'test -s /var/lib/iot/registry/demo-a.pb && test -s /var/lib/iot/registry/demo-b.pb'; then
    printf '%s\n' 'Gateway cached both authorized registry snapshots.'
    break
  fi
  if [[ "$attempt" == 9 ]]; then
    printf '%s\n' 'Expected both scoped gateway snapshots.' >&2
    exit 1
  fi
  sleep 5
done

stack() {
  IOT_STACK_IMAGE_TAG=test docker compose --env-file .env.test -p iot-platform-ci --profile test "$@"
}

generated_readings() {
  stack exec -T simulator wget -qO- http://127.0.0.1:8080/actuator/prometheus |
    awk '/^iot_simulator_generated_events_total\{/ {sum += $2; seen=1} END {if (!seen) exit 1; printf "%.0f\n", sum}'
}

before_outage=$(generated_readings)
stack stop mosquitto
for attempt in {1..10}; do
  if (( $(generated_readings) > before_outage )); then
    printf '%s\n' 'Simulator continued durable generation during the broker outage.'
    break
  fi
  if [[ "$attempt" == 10 ]]; then
    printf '%s\n' 'Expected durable simulator generation while the broker was unavailable.' >&2
    exit 1
  fi
  sleep 1
done

stack up -d --no-deps --no-build --wait --wait-timeout 45 mosquitto
stack exec -T mosquitto sh -ec '
  mosquitto_sub -V mqttv5 -h 127.0.0.1 -u gateway-a -P "$GATEWAY_A_PASSWORD" -q 1 \
    -t iot/v1/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/+/+/telemetry -C 1 -W 35 -F "%q %l"
' | grep -Eq '^1 [1-9][0-9]+$'
printf '%s\n' 'Simulator resumed authenticated QoS 1 telemetry after broker restart.'

tenant_admissions_recorded() {
  stack exec -T "$1" wget -qO- http://127.0.0.1:8080/actuator/prometheus |
    awk -v metric="$2" '
      index($1, metric "{tenant=") == 1 && $2 > 0 { tenants++ }
      END { exit tenants != 2 }
    '
}
for attempt in {1..20}; do
  if tenant_admissions_recorded gateway iot_gateway_accepted_events_total &&
     tenant_admissions_recorded simulator iot_simulator_accepted_events_total; then
    printf '%s\n' 'Gateway durably accepted and acknowledged sensor readings for both tenants.'
    break
  fi
  if [[ "$attempt" == 20 ]]; then
    printf '%s\n' 'Expected durable gateway admissions and simulator receipts for both tenants.' >&2
    exit 1
  fi
  sleep 1
done
