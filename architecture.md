# Environmental IoT streaming platform architecture

**Version 0.1 · Baseline specification v0.2, local commit `86f65f4`**

The platform is a single-laptop Docker Compose deployment. It runs six types of simulated sensors across at least two tenants, supports sampling controls from the browser, and is designed to ingest about 1 GB of unique telemetry bodies per active day over 15 days. Requirements and acceptance thresholds are defined in [specs.md](specs.md).

## 1. Deployment and service boundaries

```mermaid
flowchart LR
  User["Operator browser"]
  Vault["Independent encrypted backup drive"]
  subgraph Laptop["Laptop · Docker Compose"]
    Web["Nginx · React dashboard"]
    API["Java API · ingest, trends and commands"]
    DB[("PostgreSQL + TimescaleDB")]
    Edge["Java edge gateway"]
    Sim["Java sensor simulator"]
    Outbox[("SQLite simulator outbox")]
    Spool[("SQLite gateway spool")]
    Backup["Backup tooling"]
    User -->|"Loopback :8080"| Web
    Web -->|"Same-origin /api"| API
    Sim -->|"Readings and heartbeats"| Edge
    Edge -->|"Batches, status and command polling"| API
    Edge -->|"Sampling commands"| Sim
    API -->|"JDBC"| DB
    Sim --- Outbox
    Edge --- Spool
    DB --> Backup
    Outbox --> Backup
    Spool --> Backup
  end
  Backup --> Vault
```

Publish only the frontend port on loopback. Use private Compose networks to separate the browser proxy, sensor simulator, gateway/backend link and database. The gateway is the simulator’s ingress and command relay. The backend is the sole application service with database access. Backup tooling has a dedicated database role and writes to a mounted independent drive. Use separate named volumes for the database, spool and outbox. Give Compose services health checks and use readiness probes to verify dependencies. Startup and restart must tolerate dependencies coming up in any order.

## 2. Technology choices

| Area | Choice and rationale |
|---|---|
| Build | Temurin Java 25, Spring Boot 4.x and a Gradle Kotlin DSL multi-project build. Use a pinned Gradle wrapper. Reuse the Java toolchain and testing model from Invoice Intel. Put the backend, gateway and simulator in separate modules with independent images. |
| Backend | Spring MVC and Spring Security for APIs and operator sessions, HikariCP and PostgreSQL JDBC for explicit batch transactions, Flyway for schema migrations. Run migrations with a dedicated role and give application connections only the permissions they need. |
| Edge and simulation | Java services share versioned telemetry schemas and validation fixtures with the backend. Use Jackson and a JSON Schema Draft 2020-12 validator. Use SQLite on local volumes with WAL and `synchronous=FULL` for durable acknowledgments. Keep SQLite writes short and serialize writes within each service. |
| Transport | Internal HTTP/JSON with bounded bodies, connection and request timeouts, and per-event batch outcomes. A durable outbox and spool provide buffering at this workload without a separate message broker. |
| Database | PostgreSQL with the TimescaleDB extension for the reading hypertable and 15-minute continuous aggregate. Select and pin a compatible PostgreSQL/TimescaleDB image pair after checking native arm64 and amd64 support in M0. Keep retention and compression settings explicit and record changes in benchmark runs. |
| Frontend | React, TypeScript, Vite, Apache ECharts and TanStack Query for charting, query caching and polling. Nginx serves static assets and proxies API traffic. Use tenant-aware query keys and clear in-flight results when tenants switch. |
| Operations | Spring Boot Actuator and Micrometer for health and metrics. Capture local Prometheus metrics and Docker resource counters for the soak. Use PostgreSQL base backups plus WAL archiving and SQLite Online Backup API snapshots. |
| Verification | JUnit, ArchUnit, Testcontainers with the pinned Timescale image, Vitest and Playwright for the browser. Gatling measures the short CI workload. A separate local runner drives the calibration, pilot, soak and recovery profiles. |

Verify exact dependency and image versions in the first implementation PR and record them in the wrapper, dependency locks and Compose image references. [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html) · [SQLite durability settings](https://www.sqlite.org/pragma.html#pragma_synchronous) · [PostgreSQL continuous archiving](https://www.postgresql.org/docs/current/continuous-archiving.html)

## 3. Telemetry and idempotency

1. Each registered simulator stream emits a deterministic reading each second while enabled. A transaction persists the event and generator checkpoint before sending it. Persist a stable event ID and the original timestamp, version and value. Heartbeats use a separate path and continue while sampling is paused.
2. The gateway authenticates the simulator, resolves its tenant and sensor, and validates every event against that sensor type’s versioned JSON Schema. It acknowledges valid events after the SQLite spool commits. The simulator keeps each event until acknowledged or permanently rejected.
3. The gateway sends up to 250 events per batch or flushes after one second. It schedules tenants fairly and retries transport and transient database failures with bounded exponential backoff and jitter. The backend repeats authorization and schema validation and returns per-event outcomes for mixed batches.
4. Stage validated events for one database transaction per batch. Claim their tenant/sensor/event keys in the receipt ledger and insert readings only for newly claimed keys. The first committed version owns the key. Compare stored canonical content fingerprints for claims that already exist. Identical payloads return duplicate success and changed payloads return permanent conflict. Resolve duplicate IDs within a batch using the same rules. Write receipts and readings atomically and acknowledge after commit. A lost response replays the same IDs.
5. Remove terminal events from the spool only after the response is received and recorded. Keep reason-coded rejection and loss counters, with identifiers in diagnostic records and no raw payloads in logs. On queue saturation, return backpressure and preserve queued events.

Schema files live under `contracts/` and cover temperature, humidity, pressure, PM2.5, ambient light and oxygen concentration. Freeze canonicalization rules for each event version. Record UTF-8 body sizes at the gateway and attribute unique accepted bytes to committed identities, separately from retry traffic and storage.

## 4. Tenant-scoped data and analytics

| Relation | Key and role |
|---|---|
| `tenants`, `memberships`, `sensors` | Registry and permissions. Key sensor identity by `(tenant_id, sensor_id)` and link each sensor to its authorized gateway. |
| `readings` | Time-partitioned hypertable with `(tenant_id, sensor_id, observed_at, event_id)` as the unique key and an index starting with tenant, sensor and descending observation time. Start with one-day chunks. |
| `event_receipts` | Relational ledger keyed by `(tenant_id, sensor_id, event_id)` with a canonical fingerprint. Include it in storage sizing and retain it for the full replay and raw retention windows. |
| `sensor_state`, `commands`, `command_outbox` | Tenant-scoped liveness, desired and applied states, command versions and delivery history. Store audit outcomes with tenant and actor. |
| `sensor_15m` | Private continuous aggregate grouped by tenant, sensor and UTC bucket. Store count, sum, min and max. |

Derive tenant context from authenticated service credentials or verified operator membership. Use transaction-local tenant context for row-level policies on supported relational tables and verify that a reused connection cannot inherit a prior tenant. Establish explicit tenant predicates for hypertable queries and private aggregate access. Keep aggregate relations inaccessible to end users. Scope command dispatch, caches, queue quotas and restore verification by tenant. Use integration fixtures with overlapping sensor and event IDs.

The trend path rolls 15-minute states into hourly, daily and Monday-aligned weekly UTC buckets using total sum divided by total count. Configure a materialized-only aggregate and query the raw tail where buckets have yet to be materialized. The aggregate reader uses a single verified materialization boundary aligned to a 15-minute edge, with a consistent database snapshot so the two ranges cannot overlap or leave a gap. Test this boundary against the selected TimescaleDB version before the soak. Refresh recent completed buckets every minute. Route older accepted readings to a durable repair queue keyed by affected time bucket and coalesce repairs. Expose freshness and render empty buckets as chart gaps. [Timescale continuous aggregate refresh](https://github.com/timescale/docs/blob/latest/use-timescale/continuous-aggregates/refresh-policies.md)

## 5. Sampling controls and status

The operator submits an authenticated, CSRF-protected enable or pause request. In one transaction the backend validates membership, records the desired state, allocates a monotonic per-sensor version and writes the command to its outbox. The gateway polls for unexpired commands and relays them to the simulator. The simulator persists the applied version and sampling state before sending an ACK. The backend accepts an ACK only for its tenant, sensor and command version. A repeated idempotency key returns the prior command for an identical request and conflicts if the request changes.

The UI distinguishes requested state, last confirmed state and connectivity. An enabled stream with healthy heartbeats but no new readings for ten seconds shows stale. Three missed five-second heartbeats show offline. Use event time for freshness so old replay cannot make a stale stream appear live. Surface pending, failed, expired and superseded commands. Use an explicit restore generation when reconciling command versions after point-in-time recovery so restored state cannot replay stale instructions.

## 6. Multi-day recovery and scale measurement

- WAN loss leaves the Compose data path operational. A gateway/backend partition is absorbed by the gateway spool. Simulator/gateway isolation is absorbed by the simulator outbox. Size each queue to at least 10 decimal GB usable or 1.3 times measured seven-day on-disk growth, whichever is larger. Bound and monitor per-tenant allocation, total bytes, oldest-event age and disk headroom. Simulator downtime leaves a real gap for samples that were never generated.
- On reconnect, share ingestion capacity between live and replay traffic with fair tenant scheduling. Target total commits of at least 4× steady load, leaving at least 3× steady capacity for backlog if live traffic remains constant. The recovery test targets a seven-day backlog drained within three days. Verify while trends are under representative load.
- Take daily base backups and archive WAL continuously to the independent encrypted drive. Configure and monitor WAL segment archival lag, including during low throughput. Snapshot the two live SQLite databases with consistent backup tooling. Version a manifest with checksums, backup times, restore prerequisites and schema versions. Keep 30 days of recoverable backups through final verification. Measure backend RPO against the 15-minute target while archival is healthy and queue-volume RPO against the 24-hour snapshot target.
- Restore into isolated volumes with writes and dispatch paused. Recover PostgreSQL to the selected point using a matching PostgreSQL major and TimescaleDB extension version. Restore queue snapshots, invalidate older pending commands, reconcile simulator state and replay recoverable events through the normal receipt ledger. Independently taken backups can expose gaps, so reconcile saved counters and report unrecoverable ranges. Verify chart repair, tenant isolation and command behavior before reopening traffic. Measure queryable-stack recovery against four hours and backlog clearance separately.
- Before soaking, measure the receipt ledger, raw hypertable, indexes, aggregates, WAL, logging, both queues and independent backup usage. Report committed rates, rejects, retries and actual stored bytes, p95 ingest and trend timings, and CPU and RAM on days 1, 7 and 15. Use local dashboards and export CSV and Markdown. At 45 readings/s the reference workload is 58.32 million readings, so validate ledger growth and backup restore time early.

## 7. Repository and implementation sequence

| Milestone | Architecture deliverable |
|---|---|
| M0 | Pin tooling and verify image architecture support. Establish module layout, Compose networks and volumes, and the Invoice Intel-style `CI gate`. |
| M1 | Add tenant identities and fixtures, telemetry schemas, deterministic simulator, SQLite queues, database migrations and idempotent ingest. Verify duplicate and isolation invariants before load testing. |
| M2 | Add continuous aggregates, tail queries, historical bucket repair and React trends. Measure all four resolutions under concurrent ingest. |
| M3 | Add acknowledged sensor controls, status monitoring, failure injection, backup automation and a restore drill. |
| M4 | Run calibration, 24-hour pilot and 15 active workload days. Record hardware, versions, configuration, interruptions and observed limits. |

Use `backend/`, `gateway/`, `simulator/`, `contracts/`, `frontend/`, `perf/` and `scripts/`. Keep migration ownership with the backend. Store repeatable Compose commands and failure drills in the runbook. Verify all spec acceptance IDs through tests or experiment evidence. Apply the documented protected-trunk and squash-merge policy once the GitHub Actions gate is verified. [Invoice Intel CI policy](https://github.com/utkarshmankad/invoice-intel/blob/c62b77ab8dccabffa43323ac5ef5dc7573e9a50f/docs/ci.md)
