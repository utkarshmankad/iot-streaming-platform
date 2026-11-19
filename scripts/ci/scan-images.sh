#!/usr/bin/env bash
set -euo pipefail

scan_dir="$(mktemp -d "${RUNNER_TEMP:?}/image-scan.XXXXXX")"
trap 'rm -f "$scan_dir/image.tar"; rmdir "$scan_dir"' EXIT
cache_dir="$RUNNER_TEMP/trivy-cache"
mkdir -p "$cache_dir"

for service in backend gateway simulator frontend; do
  printf 'Scanning %s\n' "$service"
  docker save -o "$scan_dir/image.tar" "iot-platform-${service}:test"
  docker run --rm -v "$scan_dir:/scan:ro" -v "$cache_dir:/root/.cache/trivy" \
    aquasec/trivy:0.76.2 image --input /scan/image.tar --scanners vuln \
    --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1
done
