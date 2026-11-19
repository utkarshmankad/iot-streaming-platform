# Continuous integration

GitHub Actions runs on pull requests to `main`, pushes to `main`, and manual dispatches. Protected `main` requires the aggregate `CI gate`. Enable GitHub’s dependency graph before running the PR dependency review.

| Stage | What it verifies |
|---|---|
| Detect project state | Required Gradle, frontend, container, test and Gatling inputs are present. Detects Markdown and docs-only PRs. |
| PR hygiene | Conventional Commit title and task-style branch name. |
| Repo guards | Disallowed private-data and local-state paths, secret scanning, workflow lint and the checked-in ruleset. |
| Format and style | Java Spotless, frontend ESLint and TypeScript, and the production frontend build. |
| Unit tests | Java JUnit and ArchUnit tests plus Vitest. |
| Integration tests | Java integration suite, starting with the backend Spring startup test. |
| Static analysis | SpotBugs and FindSecBugs across the Java services. |
| CodeQL | Buildless Java and TypeScript scanning with security-extended queries. Error-level findings fail the stage. |
| Dependency vulnerabilities | Trivy scans locked dependencies. PR dependency review checks newly introduced high or critical findings and the GPL/AGPL denylist. |
| Container build and image scan | Hadolint checks both Dockerfiles. Build and scan the four application images for fixable high or critical findings. |
| Coverage gate | Merged JaCoCo line coverage across Java unit and integration tests, with an 85% minimum. |
| Smoke test | Starts the scanned images and dependencies in the disposable test project. Checks the dashboard, readiness and the API proxy. |
| Performance test | Runs Gatling against the dashboard and readiness endpoint with 10 users per second for 10 seconds. Requires at least 99% successful requests and p95 of at most 500 ms. Extend the scenarios as ingest, trends and control APIs become available. |
| CI gate | Rejects failed, cancelled or unexpectedly skipped stages. PR hygiene only applies to PRs. Docs-only PRs may skip performance. |

The coverage threshold applies once there is measurable application code. The only exclusions are the three Spring Boot launcher classes. A checkout containing other Java classes requires coverage data, and the verification fails below 85%. Test and report artifacts are retained for 14 days. The image archive used by runtime jobs is retained for one day.

The separate `Nightly` workflow rebuilds and rescans the application images at 03:00 IST. Its status is outside the required PR gate. Mutation testing will join the scheduled workflow after critical ingestion and command code is available.

Run the checks from a clean checkout:

```sh
./gradlew spotlessCheck test integrationTest spotbugsMain jacocoMergedReport jacocoMergedCoverageVerification
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
./scripts/stack.sh test up
bash scripts/ci/smoke.sh
./gradlew :perf:gatlingRun
./scripts/stack.sh test reset
```
