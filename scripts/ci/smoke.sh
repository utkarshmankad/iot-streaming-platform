#!/usr/bin/env bash
set -euo pipefail

url="http://127.0.0.1:${IOT_HTTP_PORT:-18080}"
curl --fail --silent --show-error "$url/healthz" | grep -Fx ok
curl --fail --silent --show-error "$url/" | grep -F '<title>Environmental IoT</title>'
api_response="$(curl --silent --show-error --header 'Accept: application/json' -o /dev/null \
  -w '%{http_code} %{content_type}' "$url/api/v1/sensors")"
[[ "$api_response" =~ ^(401|404)\ application/ ]]
