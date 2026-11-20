#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

export IOT_BROWSER_URL="http://127.0.0.1:${IOT_HTTP_PORT:-18080}"
export IOT_BROWSER_OPERATOR_PASSWORD="$(sed -n 's/^IOT_STACK_OPERATOR_A_PASSWORD=//p' .env.test)"
export IOT_BROWSER_REPORT="$PWD/build/ci/browser.xml"
test -n "$IOT_BROWSER_OPERATOR_PASSWORD"
exec npm --prefix frontend run test:browser
