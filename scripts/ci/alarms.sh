#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

stack() {
  IOT_STACK_IMAGE_TAG=test docker compose --env-file .env.test -p iot-platform-ci --profile test "$@"
}

docker run --rm -v "$PWD/observability:/rules:ro" -w /rules --entrypoint /bin/promtool   prom/prometheus:v3.16.0 test rules alerts.test.yml

await_history() {
  local filter=$1 limit=$2 attempt
  for ((attempt=1; attempt<=limit; attempt++)); do
    if python3 scripts/observe.py test history | jq -e -s "$filter" >/dev/null; then return; fi
    sleep 2
  done
  printf 'Alarm history did not satisfy %s\n' "$filter" >&2
  return 1
}

# The receiver exposes neither credentials nor history over HTTP.
stack exec -T alarm-receiver python - <<'PY'
from urllib.error import HTTPError
from urllib.request import Request, urlopen

for request, expected in [
    (Request("http://127.0.0.1:8080/webhook", data=b"{}"), 401),
    (Request("http://127.0.0.1:8080/history"), 404),
]:
    try:
        urlopen(request, timeout=3)
        raise AssertionError("Expected request to be denied")
    except HTTPError as denied:
        assert denied.code == expected, denied
PY
# The product proxy serves the application shell for unrecognized paths, never internal metrics.
curl -fsS "http://127.0.0.1:${IOT_HTTP_PORT:-18080}/actuator/prometheus" | grep -Fq '<title>Environmental IoT</title>'

# Verify real database and backup rules while keeping the alarm ledger independent.
epoch_now=$(date -u +%s)
python3 scripts/observe.py test backup-status --verified-epoch "$((epoch_now - 97200))"

trap 'stack start timescaledb >/dev/null' EXIT
stack stop timescaledb
await_history 'any(.[]; .name == "DatabaseUnavailable" and .status == "firing")' 75
await_history 'any(.[]; .name == "BackupOverdue" and .status == "firing")' 10
python3 scripts/observe.py test backup-status --verified-epoch "$epoch_now"
stack up -d --no-deps --no-build --wait --wait-timeout 45 timescaledb
trap - EXIT
await_history 'any(.[]; .name == "DatabaseUnavailable" and .status == "resolved")' 60
await_history 'any(.[]; .name == "BackupOverdue" and .status == "resolved")' 20

# A resolved row remains terminal after duplicate firing and resolved webhooks.
stack exec -T alarm-receiver python - <<'PY'
import json
import os
from datetime import datetime, timedelta, timezone
from urllib.request import Request, urlopen

url = "http://127.0.0.1:8080/webhook"
now = datetime.now(timezone.utc)
alert = {
    "fingerprint": "0000000000000001",
    "labels": {"alertname": "M33DuplicateFixture", "severity": "warning", "stage": "monitoring"},
    "annotations": {"runbook": "docs/local-deployment.md#alarms-and-metrics"},
    "startsAt": (now - timedelta(seconds=4)).isoformat(), "endsAt": now.isoformat(),
}
for status in ("firing", "firing", "resolved", "resolved", "firing"):
    request = Request(url, data=json.dumps({"alerts": [{**alert, "status": status}]}).encode(),
                      headers={"Authorization": "Bearer " + os.environ["IOT_ALARM_WEBHOOK_TOKEN"],
                               "Content-Type": "application/json"})
    with urlopen(request, timeout=3) as response:
        assert response.status == 204
PY
stack up -d --no-deps --force-recreate --no-build --wait --wait-timeout 30 alarm-receiver
await_history '([.[] | select(.name == "M33DuplicateFixture")] | length == 1 and .[0].status == "resolved")
  and any(.[]; .name == "DatabaseUnavailable" and .status == "resolved")' 5

# Simulate elapsed time in the persisted watchdog checkpoint for the laptop-sleep case.
stack stop alertmanager
trap 'stack start alertmanager >/dev/null' EXIT
stack exec -T alarm-receiver python - <<'PY'
import sqlite3
import time

with sqlite3.connect("/var/lib/iot/alarms/alarms.sqlite") as ledger:
    ledger.execute("UPDATE watchdog SET last_delivery=? WHERE id=1", (int(time.time()) - 120,))
PY
stack up -d --no-deps --force-recreate --no-build --wait --wait-timeout 30 alarm-receiver
await_history 'any(.[]; .kind == "monitoring_gap" and .ends_at == null)' 5
stack up -d --no-deps --no-build --wait --wait-timeout 30 alertmanager
trap - EXIT
await_history 'any(.[]; .kind == "monitoring_gap" and .ends_at != null)' 40

mkdir -p build/ci
python3 scripts/observe.py test history > build/ci/alarms.ndjson
printf '%s\n' 'Alarm authorization, firing, resolution, deduplication, recreation and watchdog recovery verified.'
