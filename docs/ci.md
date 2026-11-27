# Continuous integration

GitHub Actions runs on pull requests to `main`, pushes to `main`, and manual dispatches. Protected `main` requires the aggregate `CI gate`. Enable GitHub’s dependency graph before running the PR dependency review.

| Stage | What it verifies |
|---|---|
| Detect project state | Required Gradle, frontend, container, test and Gatling inputs are present. Detects Markdown and docs-only PRs. |
| PR hygiene | Conventional Commit title and task-style branch name. |
| Repo guards | Disallowed private-data and local-state paths, secret scanning, workflow lint and the checked-in ruleset. |
| Format and style | Java Spotless, browser OpenAPI validation with Redocly CLI, frontend ESLint and TypeScript, and the production frontend build. |
| Unit tests | Java JUnit and ArchUnit tests plus Vitest. |
| Integration tests | Java integration suite, starting with the backend Spring startup test. |
| Static analysis | SpotBugs and FindSecBugs across the Java services. |
| CodeQL | Buildless Java and TypeScript scanning with security-extended queries. Error-level findings fail the stage. |
| Dependency vulnerabilities | Trivy scans locked dependencies. PR dependency review checks newly introduced high or critical findings and the GPL/AGPL denylist. |
| Container build and image scan | Hadolint checks both Dockerfiles. Build and scan the four application images for fixable high or critical findings. |
| Coverage gate | Merged JaCoCo line coverage across Java unit and integration tests, with an 85% minimum. |
| Smoke test | Starts the scanned images and dependencies in the disposable test project. Checks readiness, telemetry recovery, and the production React dashboard in headless Chromium through the API proxy. |
| Performance test | Runs Gatling with five authenticated trend readers during live ingest, plus the dashboard and readiness shell load. Asserts each API resolution meets 500 ms p95 and checks the ingestion p95 against 3 s. |
| CI gate | Rejects failed, cancelled or unexpectedly skipped stages. PR hygiene only applies to PRs. Docs-only PRs may skip performance. |

The coverage threshold applies once there is measurable application code. The exclusions are the three Spring Boot launcher classes and generated Protobuf classes. A checkout containing other Java classes requires coverage data, and the verification fails below 85%. Test and report artifacts are retained for 14 days. The image archive used by runtime jobs is retained for one day.

The separate `Nightly` workflow runs at 03:00 IST and supports manual dispatch. It rebuilds and rescans the application images and runs `./gradlew mutationChecks` outside the required PR gate. PIT targets backend event fingerprints, TimescaleDB-backed admission and receipt persistence, and command expiry. It also targets shared message validation, gateway spool and delivery, and simulator command and outbox state. The backend mutation run requires Docker for the pinned TimescaleDB fixture. Per-module HTML and XML reports are retained in `mutation-report` for 30 days. The job checks that all four scopes exercised mutations and that the TimescaleDB fixture killed mutants in both the ingestion service and the receipt store. Surviving mutants and untested paths are visible in the reports. [PIT Gradle configuration](https://gradle-pitest-plugin.solidsoft.info/)

FU-06 verified seven daily Nightly runs on `main` from 21 to 27 November 2026. GitHub recorded each event as `schedule` and each run succeeded. The 27 November run passed mutation testing and image rescanning. Its artifact contains HTML and XML mutation results for all four modules. The configured trigger is 03:00 IST. GitHub created that run at 09:13 IST, so use the run timestamp when reporting actual starts. [Scheduled run](https://github.com/utkarshmankad/iot-streaming-platform/actions/runs/45935810441) · [Mutation report](https://github.com/utkarshmankad/iot-streaming-platform/actions/runs/45935810441/artifacts/15170577059) · [GitHub schedule timing](https://docs.github.com/en/actions/how-tos/troubleshoot-workflows#scheduled-workflows-running-at-unexpected-times)

## Trend and ingest performance mix

The performance job provisions a fresh two-tenant stack with 11 streams per metric and tenant (132 one-hertz streams). Three authenticated readers use tenant A and two use tenant B. All five synchronize before each query and repeat the four-query sequence three times with two seconds between requests. The shell scenario retains its 10 users per second for 10 seconds.

| Trends bucket | Aligned window ending at the end of the current bucket (UTC) | Sensors per reader |
|---|---|---|
| 15 minutes | 6 hours | 1 |
| 1 hour | 2 days | 3 |
| 1 day | 7 days | 6 |
| 1 week | 28 days, Mondays | 10 |

The gate requires 15 successful responses at each resolution and a p95 of at most 500 ms per resolution. It retains the 99% overall success and 500 ms p95 shell-plus-reader assertions. The backend records newly committed readings after PostgreSQL confirms commit, using the gateway receipt as the start time. The run compares the cumulative metric before and after Gatling. At least 95% of a minimum 132 new observations must be in the 3 s bucket. The trend report and ingest threshold evidence are uploaded from `perf/build/reports/gatling/` and `build/ci/performance-ingest.txt`. The short CI run has a warm, newly provisioned dataset. Repeat this query mix on the retained soak dataset to compare day-15 latency.

The browser smoke job verifies sign-in, tenant isolation with overlapping IDs in two tabs, sensor and metric filters, UTC ranges, stale and gap states, automatic refresh, and idempotent retries and expiry for sensor controls. The live tests verify fresh trends through Compose, confirmed pause and resume, and an offline command that expires without changing confirmed sampling. The online command check measures server acceptance to resolution over 20 alternating pause and resume requests. It writes the nearest-rank p95 to `build/ci/command-latency.txt` and fails above five seconds. The Chromium report is written to `build/ci/browser.xml`. The same job tests Prometheus production rule timings with synthetic values and verifies authenticated alarm delivery and history across real database downtime, duplicate webhooks and receiver recreation. It uploads `build/ci/alarms.ndjson`.

## Repository protection and updates

GitHub enforces the active `main-trunk` ruleset on the public repository. Pull requests require resolved review threads and the strict GitHub Actions `CI gate`. GitHub permits squash merges, maintains linear history, blocks deletion and force pushes, and has no bypass actors. Squash auto-merge and merged-branch cleanup are enabled.

The dependency graph, Dependabot alerts and security updates, secret scanning, and secret scanning push protection are enabled in repository settings. The CodeQL workflow remains the code scanning configuration.

Dependabot opens grouped weekly version update PRs on Mondays for Gradle, frontend npm, Dockerfiles, Docker Compose images and GitHub Actions. The checked-in [Dependabot configuration](../.github/dependabot.yml) groups minor and patch updates for package and image ecosystems. GitHub Actions updates share one group. Dependabot PRs use the same required CI checks.

Run the checks from a clean checkout:

```sh
./gradlew spotlessCheck test integrationTest spotbugsMain jacocoMergedReport jacocoMergedCoverageVerification
npm --prefix frontend ci
npm --prefix frontend run lint
npm exec --yes --package=@redocly/cli@2.82.0 -- redocly lint --extends=spec contracts/openapi/browser-v1.yaml
npm --prefix frontend test
npm --prefix frontend run build
(cd frontend && npm exec -- playwright install --only-shell chromium)
IOT_STACK_SIM_STREAMS_PER_TYPE=11 ./scripts/stack.sh test up
bash scripts/ci/smoke.sh
bash scripts/ci/browser.sh
bash scripts/ci/performance.sh
./scripts/stack.sh test reset
```

The point-in-time recovery drill runs in a fresh disposable source project after telemetry fault checks. It restores a timestamp into isolated volumes, verifies tenant isolation, rejects stale controls, replays queued event IDs and tests checksum rejection. It uploads `build/ci/recovery.json` and the isolated Compose logs.
