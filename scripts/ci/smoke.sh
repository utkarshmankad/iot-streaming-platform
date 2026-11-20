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

for attempt in {1..20}; do
  if stack exec -T timescaledb psql -X -qAt -v ON_ERROR_STOP=1 -U iot -d iot \
      -c 'SELECT count(DISTINCT tenant_id) FROM telemetry.readings' | grep -Fxq 2; then
    printf '%s\n' 'TimescaleDB committed simulator readings for both tenants.'
    break
  fi
  if [[ "$attempt" == 20 ]]; then
    printf '%s\n' 'Expected committed TimescaleDB readings for both tenants.' >&2
    exit 1
  fi
  sleep 1
done

cookies="$(mktemp)"
latency_samples="$(mktemp)"
trap 'rm -f "$cookies" "$latency_samples"' EXIT
csrf="$(curl --fail --silent --show-error --cookie-jar "$cookies" "$url/api/v1/session/csrf")"
csrf_header="$(jq -er .headerName <<< "$csrf")"
csrf_token="$(jq -er .token <<< "$csrf")"
operator_password="$(sed -n 's/^IOT_STACK_OPERATOR_A_PASSWORD=//p' .env.test)"
test -n "$operator_password"
printf '%s' "$operator_password" | jq -Rs '{username: "operator-a", password: .}' |
  curl --fail --silent --show-error --cookie "$cookies" --cookie-jar "$cookies" \
    --header "$csrf_header: $csrf_token" --header 'Content-Type: application/json' \
    --data-binary @- "$url/api/v1/session" |
  jq -e '.activeTenantId == "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"' >/dev/null
sensor_id="$(curl --fail --silent --show-error --cookie "$cookies" "$url/api/v1/sensors?limit=1" |
  jq -er 'select(.items | length == 1) | .items[0] | select(.lastObservedAt != null).sensorId')"
bounds="$(python3 - <<'PY'
from datetime import datetime, timedelta, timezone

now = datetime.now(timezone.utc)
start = now.replace(minute=now.minute // 15 * 15, second=0, microsecond=0)
for stamp in (start - timedelta(minutes=15), start + timedelta(minutes=15)):
    print(stamp.isoformat().replace("+00:00", "Z"), end=" ")
PY
)"
read -r from to <<< "$bounds"
curl --fail --silent --show-error --cookie "$cookies" --get \
  --data-urlencode "sensorId=$sensor_id" --data-urlencode "from=$from" \
  --data-urlencode "to=$to" --data-urlencode 'bucket=15m' "$url/api/v1/trends" |
  jq -e --arg sensor_id "$sensor_id" \
    '.bucket == "15m" and (.series | length == 1)
    and (.series[0] | .sensorId == $sensor_id and (.buckets | length == 2))' >/dev/null
printf '%s\n' 'Browser can authenticate and query tenant-scoped trends through Nginx.'

# Capture a new CSRF token after login rotated the session.
csrf="$(curl --fail --silent --show-error --cookie "$cookies" "$url/api/v1/session/csrf")"
csrf_header="$(jq -er .headerName <<< "$csrf")"
csrf_token="$(jq -er .token <<< "$csrf")"

command() {
  local enabled=$1 key=$2
  curl --fail --silent --show-error --cookie "$cookies" \
    --header "$csrf_header: $csrf_token" --header 'Content-Type: application/json' \
    --header "Idempotency-Key: $key" --data "{\"enabled\":$enabled}" \
    "$url/api/v1/sensors/$sensor_id/commands" | jq -er .commandId
}

await_command() {
  local id=$1 enabled=$2 reply attempt
  for attempt in {1..20}; do
    if reply="$(curl --fail --silent --show-error --cookie "$cookies" "$url/api/v1/commands/$id")" &&
      jq -e --argjson enabled "$enabled" '.status == "applied" and .confirmedEnabled == $enabled' \
        >/dev/null <<< "$reply"; then
      printf '%s\n' "$reply"
      return
    fi
    sleep 0.5
  done
  printf 'The simulator did not confirm the requested sampling state.\n' >&2
  return 1
}

await_sensor() {
  local connectivity=$1 samples=$2 confirmed=$3 attempt
  for attempt in {1..25}; do
    if curl --fail --silent --show-error --cookie "$cookies" "$url/api/v1/sensors?limit=1" |
      jq -e --arg connectivity "$connectivity" --arg samples "$samples" --argjson confirmed "$confirmed" \
        '.items[0] | .connectivity == $connectivity and .sampleStatus == $samples
        and .confirmedEnabled == $confirmed' >/dev/null; then
      return
    fi
    sleep 1
  done
  printf 'Expected %s connectivity and %s sample status.\n' "$connectivity" "$samples" >&2
  return 1
}

readings_after_command() {
  stack exec -T timescaledb psql -X -qAt -v ON_ERROR_STOP=1 -U iot -d iot -v command_id="$1" <<'SQL'
SELECT count(*)
FROM telemetry.readings r
JOIN registry.commands c USING (tenant_id, sensor_id)
WHERE c.command_id = :'command_id' AND r.observed_at > c.applied_at;
SQL
}

run_key="$(openssl rand -hex 8)"
pause="$(command false "smoke-pause-$run_key")"
await_command "$pause" false >/dev/null
await_sensor online paused false
sleep 2
[[ "$(readings_after_command "$pause")" == 0 ]]
resume="$(command true "smoke-resume-$run_key")"
await_command "$resume" true >/dev/null
for attempt in {1..12}; do
  if (( $(readings_after_command "$resume") > 0 )); then
    printf '%s\n' 'Simulator confirmed pause and resume and produced new readings after resume.'
    break
  fi
  if [[ "$attempt" == 12 ]]; then
    printf '%s\n' 'Expected a new reading after simulator resume.' >&2
    exit 1
  fi
  sleep 0.5
done

await_sensor online fresh true
for sequence in {1..20}; do
  enabled=true
  if (( sequence % 2 == 1 )); then enabled=false; fi
  issued="$(command "$enabled" "smoke-latency-$sequence-$run_key")"
  await_command "$issued" "$enabled" >> "$latency_samples"
done
mkdir -p build/ci
python3 - "$latency_samples" <<'PY' | tee build/ci/command-latency.txt
from datetime import datetime
import json
from math import ceil
import sys

def instant(stamp):
    return datetime.fromisoformat(stamp.replace('Z', '+00:00'))

with open(sys.argv[1], encoding='utf-8') as samples:
    results = [json.loads(line) for line in samples]
latencies = sorted((instant(row['resolvedAt']) - instant(row['createdAt'])).total_seconds() * 1000
                   for row in results)
assert len(latencies) == 20 and latencies[0] >= 0, latencies
p95 = latencies[ceil(0.95 * len(latencies)) - 1]
print(f'Online commands: {len(latencies)}; p95: {p95:.0f} ms; max: {latencies[-1]:.0f} ms')
assert p95 <= 5000, 'Online command p95 exceeded five seconds'
PY

stack stop simulator
await_sensor offline stale true
offline="$(command false "smoke-offline-$run_key")"
curl --fail --silent --show-error --cookie "$cookies" "$url/api/v1/commands/$offline" |
  jq -e '.status == "pending" and .requestedEnabled == false' >/dev/null
for attempt in {1..65}; do
  if curl --fail --silent --show-error --cookie "$cookies" "$url/api/v1/commands/$offline" |
    jq -e '.status == "expired"' >/dev/null; then
    break
  fi
  if [[ "$attempt" == 65 ]]; then
    printf '%s\n' 'Expected the offline command to expire.' >&2
    exit 1
  fi
  sleep 1
done
stack up -d --no-deps --no-build --wait --wait-timeout 45 simulator
await_sensor online fresh true
curl --fail --silent --show-error --cookie "$cookies" "$url/api/v1/sensors?limit=1" |
  jq -e --arg id "$offline" '.items[0] | .desiredEnabled == false and .confirmedEnabled == true
    and (.latestCommand | .commandId == $id and .status == "expired")' >/dev/null
printf '%s\n' 'The offline pause expired and reconnection preserved the last confirmed state.'
