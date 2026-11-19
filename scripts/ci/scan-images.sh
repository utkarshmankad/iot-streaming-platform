#!/usr/bin/env bash
set -euo pipefail

cache_dir="${RUNNER_TEMP:?}/trivy-cache"
mkdir -p "$cache_dir"

for service in backend gateway simulator frontend; do
  printf 'Scanning %s\n' "$service"
  docker save "iot-platform-${service}:test" |
    docker run --rm -i --entrypoint /bin/sh -v "$cache_dir:/root/.cache/trivy" \
      aquasec/trivy:0.76.2 -ec '
        cat > /tmp/image.tar
        exec trivy image --input /tmp/image.tar --scanners vuln \
          --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1
      '
done
