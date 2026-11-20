#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

gateway_commit_counts() {
  IOT_STACK_IMAGE_TAG=test docker compose --env-file .env.test -p iot-platform-ci --profile test \
    exec -T backend wget -qO- http://127.0.0.1:8080/actuator/prometheus |
    awk '
      /^iot_ingest_gateway_to_commit_seconds_bucket\{le="3(\.0+)?",?\}/ { within = $2; found++ }
      /^iot_ingest_gateway_to_commit_seconds_count / { total = $2; found++ }
      END { if (found != 2) exit 1; printf "%.0f %.0f\n", within, total }
    '
}

if [[ ${1:-} == snapshot ]]; then
  gateway_commit_counts
  exit
fi

url="http://127.0.0.1:${IOT_HTTP_PORT:-18080}"
export IOT_PERF_A_PASSWORD="$(sed -n 's/^IOT_STACK_OPERATOR_A_PASSWORD=//p' .env.test)"
export IOT_PERF_B_PASSWORD="$(sed -n 's/^IOT_STACK_OPERATOR_B_PASSWORD=//p' .env.test)"
test -n "$IOT_PERF_A_PASSWORD" && test -n "$IOT_PERF_B_PASSWORD"

export IOT_PERF_SCRIPT="$PWD/scripts/ci/performance.sh"
export IOT_PERF_SNAPSHOTS="$(mktemp -d)"
cleanup() {
  rm -f "$IOT_PERF_SNAPSHOTS/before" "$IOT_PERF_SNAPSHOTS/after"
  rmdir "$IOT_PERF_SNAPSHOTS"
}
trap cleanup EXIT

# Start the reader measurement after generation, delivery and committed timing samples are active.
for attempt in {1..30}; do
  if counts="$(gateway_commit_counts)"; then
    read -r before_within before_total <<< "$counts"
    if (( before_total >= 132 )); then break; fi
  fi
  if (( attempt == 30 )); then
    printf '%s\n' 'Expected committed telemetry before measuring trends.' >&2
    exit 1
  fi
  sleep 1
done

./gradlew :perf:gatlingRun "-Dperf.baseUrl=$url" --no-daemon "$@"

read -r before_within before_total < "$IOT_PERF_SNAPSHOTS/before"
read -r after_within after_total < "$IOT_PERF_SNAPSHOTS/after"
mkdir -p build/ci
awk -v within="$(( after_within - before_within ))" -v total="$(( after_total - before_total ))" '
  BEGIN {
    fraction = total > 0 ? 100 * within / total : 0
    printf "Fresh commits during the reader test: %d\nWithin 3s of gateway receipt: %d (%.2f%%)\n", total, within, fraction
    if (total < 132 || within < 0 || within > total || fraction < 95) exit 1
  }
' | tee build/ci/performance-ingest.txt
