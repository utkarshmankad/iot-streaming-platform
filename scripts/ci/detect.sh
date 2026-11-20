#!/usr/bin/env bash
set -euo pipefail

for file in gradlew build.gradle.kts gradle/wrapper/gradle-wrapper.jar \
  backend/gradle.lockfile gateway/gradle.lockfile simulator/gradle.lockfile contracts/gradle.lockfile \
  frontend/package.json frontend/package-lock.json frontend/eslint.config.mjs \
  Dockerfile frontend/Dockerfile compose.yaml scripts/stack.sh \
  contracts/openapi/browser-v1.yaml perf/build.gradle.kts perf/gradle.lockfile \
  perf/src/gatling/resources/logback.xml frontend/playwright.config.mjs \
  scripts/ci/browser.sh scripts/ci/performance.sh; do
  test -s "$file" || { printf 'Required CI input missing: %s\n' "$file" >&2; exit 1; }
done
test -n "$(git ls-files perf/src/gatling/java)"
test -n "$(git ls-files frontend/browser)"
test -n "$(git ls-files backend/src/test gateway/src/test simulator/src/test)"
test -n "$(git ls-files backend/src/integrationTest gateway/src/integrationTest simulator/src/integrationTest)"

docs_only=false
if [[ -n "${1:-}" ]]; then
  git diff --name-only -z "$1" HEAD > "${RUNNER_TEMP:?}/iot-ci-paths"
  docs_only=true
  changed=false
  while IFS= read -r -d '' file; do
    changed=true
    case "$file" in
      docs/*|*.md) ;;
      *) docs_only=false ;;
    esac
  done < "$RUNNER_TEMP/iot-ci-paths"
  if [[ "$changed" == false ]]; then docs_only=false; fi
fi
printf 'docs_only=%s\n' "$docs_only" >> "${GITHUB_OUTPUT:?}"
printf 'Scaffold inputs present. Docs-only PR: %s\n' "$docs_only"
