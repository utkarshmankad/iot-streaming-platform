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
