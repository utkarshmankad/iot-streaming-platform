# Environmental IoT streaming platform implementation plan

**Version 0.1 · Baseline [specs.md v0.5](specs.md) and [architecture.md v0.5](architecture.md)**

Repository baseline: `architecture.md` is recorded in local commit `af3a48d`. This plan uses M0 for scaffolding and environment setup, then builds each feature on that foundation. Deployment means a local Docker Compose stack on the laptop. GitHub hosts the source and runs CI.

## How to work through the plan

Each checklist item is a short-lived branch and pull request from current `main`, named `<type>/<task-id>-<description>`. Use a Conventional Commit title and include the relevant spec IDs, verification evidence and any known limits. Split items that cannot land independently into smaller task IDs. Check off a task in the same PR that delivers it. Keep unfinished feature paths behind configuration until they can pass a full smoke test.

## M0 · Scaffold the project and development and deployment environments

- [x] **M0.1 · Clean repository setup.** Land this documentation baseline through the repository bootstrap PR. Set `main` as trunk. Add the initial README, local setup guide, configuration conventions and ignore rules for credentials, local state, logs, backup files and benchmark exports. Establish a one-task PR template and CODEOWNERS. *(GIT-01, GIT-02)*
- [x] **M0.2 · Toolchain and source tree.** Create the Gradle Kotlin DSL root and separate `backend/`, `gateway/` and `simulator/` Java modules. Scaffold `frontend/` with React, TypeScript and Vite. Add `contracts/openapi/`, `contracts/proto/`, `perf/`, `observability/` and `scripts/`. Pin Temurin Java 25, Spring Boot, Gradle Wrapper, Node and npm. Commit dependency locks and verify the chosen PostgreSQL/TimescaleDB and MQTT images for laptop and CI architectures. *(REQ-01, ING-01, CI-01)*
- [ ] **M0.3 · Development environment.** Supply `.env.example`, per-environment configuration, disposable credential setup and documented commands for build, lint, unit and integration tests, frontend hot reload, debugging and stack teardown. A dev Compose override can expose needed debug ports on loopback. Validate from a clean checkout without relying on installed application dependencies outside the documented toolchain. *(REQ-01, ING-04, OPS-01)*
- [ ] **M0.4 · Local deployment environment.** Build separate images for simulator, gateway, Java API and React/Nginx. Wire Mosquitto, TimescaleDB, Prometheus, Alertmanager and alarm-receiver scaffolds. Set up private networks, health/readiness checks, named volumes for persistent state and bounded container logs. Publish only the dashboard on loopback by default. Add local deployment and test profiles, a separate CI project name and disposable CI volumes. Demonstrate that the built stack can restart with internet access blocked after images are present. *(REQ-01, OPS-01, OBS-01, OBS-03, FT-01)*
- [ ] **M0.5 · CI bootstrap.** Set up GitHub Actions for PRs, pushes to `main` and manual dispatch. Start with branch/title hygiene, secret and tracked-data guards, actionlint, Java formatting and test infrastructure, frontend checks, image lint/build/scan and Compose health smoke. Add the remaining Invoice Intel-style checks as their components land, including merged JaCoCo coverage ≥85%, static analysis, CodeQL, dependency review and Gatling. Aggregate all expected jobs into `CI gate`. Validate docs-only skip behavior and schedule nightly mutation and image checks as their inputs become available. *(CI-01, CI-02, CI-03)*
- [ ] **M0.6 · Branch protection.** Once the Actions check is visible, apply and verify `main-trunk`: required PRs, strict `CI gate`, squash-only merge, linear history, resolved threads, no bypass, and no force-push or deletion. Confirm GitHub plan support for the private repository and document any owner decision needed to enable enforcement. Configure available security features and grouped Dependabot updates. *(GIT-02, CI-03)*

**Exit gate.** A clean clone can build, start the dev and local deployment profiles, open the React shell, and pass the Compose health smoke and CI gate. Versions, configuration, persistent destinations and reset procedures are documented. Verify branch protection before marking its task complete.

## M1 · Provision tenants and deliver the telemetry path

- [ ] **M1.1 · Tenant and registry foundation.** Add Flyway migrations for tenant memberships, gateway and device assignment, sensor registry, schema-version metadata and sensor state. Implement tenant authorization, credential provisioning, broker topic ACL generation and verified gateway registry snapshots. Seed two tenants with overlapping sensor and event IDs. *(TEN-01–03, ING-04)*
- [ ] **M1.2 · Payload contracts and generator.** Check in Protobuf messages, per-type validators and fixtures for all six simulated sensor types. Implement seeded one-hertz generation, byte accounting, heartbeats and durable simulator checkpoints and outbox. Publish over MQTT 5 and correlate application receipts separately from PUBACK. *(REQ-02–04, ING-01–02)*
- [ ] **M1.3 · Gateway spool.** Implement MQTT subscriptions, topic/identity and schema checks, atomic SQLite enqueue, durable receipts, and per-tenant queue budgets. Flush HTTP Protobuf batches at 250 records or one second. Add bounded retries, fair live/replay scheduling and permanent rejection accounting. *(ING-01–02, TEN-02, OPS-04)*
- [ ] **M1.4 · Idempotent backend ingest.** Create the TimescaleDB hypertable, tenant/time indexes, and receipt ledger. Revalidate events and transactionally commit ledger and reading changes. Return stable per-event outcomes for mixed batches. Test concurrent duplicates, changed-content conflicts and retries after a lost commit response. *(ING-01–03, AC-09)*
- [ ] **M1.5 · Telemetry smoke and reliability verification.** Add CI tests for tenant MQTT isolation, simulator silence, gateway and broker restart, lost application receipt and backend/database failure. Assert a traceable terminal result for each generated event after drain. Wire the full-path smoke test into `CI gate`. *(FT-01–03, AC-02, AC-09–10)*

**Exit gate.** Two tenants send validated readings from the simulator through MQTT and the durable gateway to TimescaleDB. Restart and replay checks prove original event identity is preserved and duplicate rows are suppressed.

## M2 · Ship the trend API and React dashboard

- [ ] **M2.1 · Analytics storage.** Implement per-tenant 15-minute continuous aggregates, a consistent raw-tail boundary, weighted rollups and durable repair for late events. Test UTC and Monday alignment, gaps, partial buckets and late arrivals. *(UI-02–03, AC-04)*
- [ ] **M2.2 · Read API and sessions.** Complete browser OpenAPI definitions and implement tenant-aware login/session behavior, sensor list and trends endpoints. Enforce pagination, bucket alignment, 30-day history and ten-sensor query limits. Test boundary and error responses, tenant switching and isolation. *(UI-01–02, TEN-01–03)*
- [ ] **M2.3 · Dashboard and performance gate.** Add tenant selection, registry and sensor status, the four trend resolutions, freshness, partial and empty buckets, polling and failures. Add browser and Gatling tests, including five concurrent readers during ingest. Wire short performance assertions into CI. *(UI-01–03, AC-01, AC-03–04, CI-01–02)*

**Exit gate.** The dashboard displays accurate tenant-scoped results at all four resolutions. Short load tests report ingest and trend latency and compare them with AC-03.

## M3 · Ship acknowledged controls, alarms and recovery

- [ ] **M3.1 · Control APIs and durable command state.** Implement the command create/status API, idempotency, operator permissions, outbox, expiry and monotonic per-sensor versions. Add gateway polling and MQTT relay, durable simulator apply and acknowledgment. Verify retries, expired and superseded commands, and tenant boundaries. *(CTL-01–02, TEN-01–03, AC-05)*
- [ ] **M3.2 · Control UI and liveness.** Add per-sensor on/off actions and show desired, pending and confirmed state. Distinguish stale readings, lost heartbeat and confirmed pause. Test the online five-second p95 command target and offline pending/expiry behavior. *(UI-01, CTL-01–02, AC-05)*
- [ ] **M3.3 · Observability and alarms.** Add pipeline metrics, structured logs, daily exports and capacity alerts. Configure Prometheus rules, Alertmanager routing and a persistent SQLite alarm ledger independent of TimescaleDB. Test firing, resolution, duplicate notifications, receiver recreation, access control and missing-metrics behavior. *(OBS-01–04, AC-11)*
- [ ] **M3.4 · Backup and restore tooling.** Add PostgreSQL base backup and continuous WAL archival, SQLite online queue snapshots, manifests, checksums and isolated restore commands. Document target storage, backup expiry, recovery reconciliation and stale-command prevention. Measure RPO, restore time and backlog drain separately. *(OPS-04–06, AC-07–08)*
- [ ] **M3.5 · Fault suites and operational handoff.** Implement the remaining CI fault scenarios, package the pilot fault profile and runbook, and attach evidence per architecture §9. Test fault durations, alerts, tenant isolation and reconciled readings. Ensure CI artifacts are retained and confirm nightly checks and security jobs run. *(FT-01–03, AC-09–12, CI-02–03)*

**Exit gate.** A browser pause/resume round trip succeeds only after simulator confirmation. CI exercises the short failure suite, and all commands and evidence locations for the outage and restore drills are documented.

## M4 · Calibrate, pilot, soak and report

- [ ] **M4.1 · One-hour calibration.** Measure original binary payload size, choose a one-hertz sensor count for 1 GB per active day across at least two tenants, and estimate raw, index, ledger, WAL, queue and backup growth. Record hardware, versions, Compose limits, seeds and headroom. *(REQ-03–04, OPS-01–02)*
- [ ] **M4.2 · 24-hour pilot and extended fault tests.** Validate 1 GB/day ±5%, throughput and latency, and resource headroom before starting the long run. Run WAN-only isolation, capacity saturation, the multi-day backlog test or its specified accelerated alternative, and an isolated restore and alarm watchdog drill. Record any actual gaps, RPO and recovery times. Fix failures and repeat affected checks. *(OPS-02–06, OBS-04, AC-02–03, AC-07–08, AC-11–12)*
- [ ] **M4.3 · Fifteen active workload days.** Run the calibrated profile and capture daily evidence locally. Record wall-clock interruptions and any extension needed to reach 15 active days. Keep baseline sensors enabled and mark fault intervals or use a separate control-test sensor. Compare days 1, 7 and 15 at the same query shapes and concurrency. *(REQ-03–04, OPS-02–03, AC-02–06)*
- [ ] **M4.4 · Reconcile and publish the local run report.** Drain queues, reconcile identities and losses per tenant, verify restore evidence, and export CSV and Markdown covering volume, storage growth, performance, downtime, alarms and limits. Link each acceptance criterion to its evidence in the report. *(AC-01–12)*

**Exit gate.** The report records pass/fail and supporting evidence for every acceptance criterion. Performance and recovery targets are confirmed by observations from the actual laptop deployment.

## Acceptance evidence map

| Spec acceptance | Primary evidence |
|---|---|
| AC-01 · Clean startup and restart | M0 health smoke and M1–M3 integrated Compose/browser smoke |
| AC-02–03 · Volume, reconciliation and latency | M4 calibration, pilot and soak measurements |
| AC-04–05 · Correct trends and controls | M2 analytics/browser tests and M3 command tests, with M4 spot checks |
| AC-06 · Run report | M4 CSV and Markdown exports |
| AC-07–08 · Extended outage and restore | M4 pilot queue/replay and restore drill results |
| AC-09–10 · Schema, duplicates and MQTT | M1 ingest and CI fault results |
| AC-11–12 · Alerts, failure suites and tenant boundaries | M3 CI artifacts and M4 pilot evidence |

## Follow-ups

Record validated work discovered during implementation with a stable ID, originating task or PR, expected outcome, and dependencies. Use checklist entries. The hourly runner can select an actionable follow-up after earlier eligible milestone tasks. A follow-up requiring an owner decision stays blocked until the decision is recorded.

## Change history

Record changes to milestone sequence, tasks or exit gates here in the same pull request as the edit. Add one entry per change request with its date or version, a short summary, and the affected IDs or sections when applicable. Use a PR or commit reference when available. When a request changes more than one document, record its effect in each affected file.

| Baseline or change | Local commit | Delivery changes recorded |
|---|---|---|
| v0.1 | `c0ef289` | Established M0 as scaffolding from scratch, including development, laptop deployment and CI. Sequenced telemetry, trends, controls and recovery, then calibration, pilot and 15-day soak. Added milestones, exit gates and acceptance evidence. |
| Documentation tracking | This revision | Added a record of delivery-plan changes and the convention for future entries. |
| Hourly task runner | This revision | Added a Follow-ups section for implementation discoveries and owner decisions. |
| M0.1 repository setup | This revision | Recorded the public repository decision, protected trunk and bootstrap CI from PR #1. Added onboarding, contribution and local file conventions. |
| M0.2 toolchain scaffold | This revision | Added the three Java modules, React shell, locked dependencies, and the planned source directories. Verified build and image-platform availability. |
