#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export PYTHONDONTWRITEBYTECODE=1

label=label=com.docker.compose.project=iot-platform-ci
if [[ -f .env.backup-test || -n "$(docker ps -aq --filter "$label")$(docker volume ls -q --filter "$label")" ]]; then
  printf '%s\n' 'Point-in-time drill needs a fresh, unconfigured test project.' >&2
  exit 1
fi
mkdir -p build/ci
target=$(mktemp -d "$PWD/build/ci/recovery-target.XXXXXX")
stamp=$(openssl rand -hex 4)
restore_name="ci-$stamp"
project="iot-restore-$restore_name"
restore_port=18084
export IOT_STACK_IMAGE_TAG=test

source_db() {
  docker compose --env-file .env.test --profile test -p iot-platform-ci exec -T timescaledb \
    psql -XqAt -U iot -d iot -v ON_ERROR_STOP=1 -c "$1"
}
restore_stack() {
  IOT_HTTP_PORT=$restore_port IOT_RESTORE_WAL="$target/wal/$system_id" \
    docker compose --env-file "$target/$backup_id/stack.env" --profile test -p "$project" \
    -f compose.yaml -f compose.recovery.yaml "$@"
}
restored_db() {
  restore_stack exec -T timescaledb psql -XqAt -U iot -d iot -v ON_ERROR_STOP=1 -c "$1"
}
cleanup() {
  if [[ -n "${backup_id:-}" && -n "${system_id:-}" ]]; then
    restore_stack logs --no-color > build/ci/recovery-compose.log 2>&1 || true
    restore_stack down --volumes || true
  fi
  ./scripts/stack.sh test reset || true
  rm -f .env.backup-test
  rm -rf "$target"
}
trap cleanup EXIT
python3 scripts/recovery.py test configure "$target"
./scripts/stack.sh test up-built

wait_for() {
  local query=$1 expected=$2 attempt
  for attempt in {1..30}; do
    if [[ "$(source_db "$query")" == "$expected" ]]; then return; fi
    sleep 1
  done
  printf '%s\n' 'Live backup fixture did not become ready.' >&2
  return 1
}

wait_for 'SELECT count(DISTINCT tenant_id) FROM telemetry.readings' 2
source_db "CREATE TABLE public.recovery_fixture (sample text PRIMARY KEY);
  INSERT INTO public.recovery_fixture VALUES ('base')"
# Cache the registry through ordinary ingest before creating the delivery partition.
docker compose --env-file .env.test --profile test -p iot-platform-ci stop backend
source_db "INSERT INTO registry.commands (tenant_id,command_id,sensor_id,device_id,gateway_id,operator_id,
      idempotency_key,version,requested_enabled,created_at,expires_at)
  SELECT s.tenant_id,'00000000-0000-4000-8000-000000000044',s.sensor_id,d.device_id,
         d.gateway_id,m.operator_id,'restore-fixture',77,false,now(),now()+interval '1 hour'
  FROM registry.sensors s JOIN registry.devices d USING(tenant_id,device_id)
  JOIN registry.operator_memberships m USING(tenant_id)
  ORDER BY s.tenant_id,s.sensor_id LIMIT 1"
sleep 4
backup_id=$(python3 scripts/recovery.py test take | tail -1)
[[ -n "$backup_id" ]]
system_id=$(jq -er .database.system_id "$target/$backup_id/manifest.json")
jq -e '.queued.gateway | any(.[]; . > 0)' "$target/$backup_id/manifest.json" >/dev/null
python3 scripts/recovery.py test verify "$backup_id" >/dev/null

source_db "INSERT INTO public.recovery_fixture VALUES ('wanted')"
recovery_time=$(source_db "SELECT clock_timestamp()")
sleep 1
source_db "INSERT INTO public.recovery_fixture VALUES ('too-late')"
segment=$(source_db "SELECT pg_walfile_name(pg_current_wal_insert_lsn())")
source_db 'SELECT pg_switch_wal()' >/dev/null
for attempt in {1..45}; do
  [[ -f "$target/wal/$system_id/$segment.gz" ]] && break
  sleep 1
done
[[ -f "$target/wal/$system_id/$segment.gz" ]]
report=$(python3 scripts/recovery.py test restore "$backup_id" "$restore_name" \
  --target-time "$recovery_time" --port "$restore_port" | tail -1)
[[ "$(restored_db "SELECT string_agg(sample,',' ORDER BY sample) FROM public.recovery_fixture")" == 'base,wanted' ]]
[[ "$(restored_db "SELECT status FROM registry.commands WHERE command_id='00000000-0000-4000-8000-000000000044'")" == "superseded" ]]
[[ "$(restored_db "SELECT version_floor>77 FROM registry.restore_generations")" == "t" ]]
[[ "$(source_db 'SELECT count(*) FROM public.recovery_fixture')" == 3 ]]
curl -fsS "http://127.0.0.1:$restore_port/healthz" | grep -Fx ok

# The isolated hold serves browser reads and blocks mutating sensor controls.
python3 scripts/ci/recovery_browser.py .env.test "$restore_port" hold
python3 scripts/recovery.py test resume "$restore_name" --drain-seconds 90
python3 scripts/ci/recovery_browser.py .env.test "$restore_port" resumed
incident_at=$(source_db "SELECT clock_timestamp()")
python3 scripts/recovery.py test measure-rpo "$restore_name" --incident-at "$incident_at"
jq -e '.hold == false and .seconds_to_queryable > 0 and .seconds_to_confirm_backlog >= 0
  and (.tenant_inventory | length == 2)
  and (.queues_after_replay | length > 0 and all(.[]; .unconfirmed == 0))' "$report" >/dev/null
cp "$report" build/ci/recovery.json

# Missing checkpoint WAL must fail checksum verification before recovery starts.
checkpoint_wal=$(jq -er .checkpoint_wal.name "$target/$backup_id/manifest.json")
archive_file="$target/wal/$system_id/$checkpoint_wal"
mv "$archive_file" "$archive_file.fixture"
if python3 scripts/recovery.py test verify "$backup_id"; then
  printf '%s\n' 'Expected verification to reject the incomplete archive.' >&2
  exit 1
fi
mv "$archive_file.fixture" "$archive_file"

# A modified SQLite snapshot must fail verification before it can be restored.
printf 'damage' >> "$target/$backup_id/gateway.sqlite"
if python3 scripts/recovery.py test verify "$backup_id"; then
  printf '%s\n' 'Expected the tampered snapshot to fail verification.' >&2
  exit 1
fi
printf '%s\n' 'Point-in-time recovery, isolated control replay, tenant boundaries and checksum rejection passed.'
