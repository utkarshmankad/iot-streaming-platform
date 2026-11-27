# Local Docker deployment

Install Docker with Compose and OpenSSL. From the repository root, build and start the stack:

```sh
./scripts/stack.sh local up
./scripts/stack.sh local status
curl --fail http://127.0.0.1:8080/healthz
```

Open the dashboard at `http://127.0.0.1:8080`. Change the published loopback port with `IOT_HTTP_PORT` when needed. The first build downloads pinned images and dependencies. The stack includes separate backend, gateway, simulator and frontend images, plus TimescaleDB, Mosquitto, Prometheus, Alertmanager and the durable alarm receiver.

The three Java images share a Docker build stage. It downloads and verifies the pinned Gradle distribution before copying application source. Builds on the same Docker builder reuse that layer when Java source changes. All three images reuse the shared compilation while build inputs match. A new builder or a change to the wrapper inputs or base image can require a fresh download. The stack script builds one service at a time when using the classic Docker builder so the next service can reuse the shared layers. If you invoke Compose directly with the classic builder, use `docker compose --parallel 1 build` with the same environment and profile options. [Docker build caching](https://docs.docker.com/build/cache/optimize/)

The stack script writes generated database, broker, gateway and demo operator credentials to an ignored, owner-only `.env.stack`. It adds missing registry credentials to existing files without replacing their database or broker probe passwords. Keep this file with the local deployment data volume because PostgreSQL reads the initialization password on first startup.

Two one-shot Compose jobs create the database roles and schemas, enable TimescaleDB and run Flyway migrations. The demo provisioner registers two tenants with overlapping sensor IDs and produces an MQTT ACL and a simulator assignment file from their active assignments. Repeated provisioning preserves existing registry rows and status changes. The backend uses the restricted runtime role and the broker loads separate credentials and topic rules for the two gateways and two devices.

To refresh the projections after local registry changes, run the provisioner again and recreate the broker and simulator so both load the new assignments.

```sh
docker compose --env-file .env.stack --profile local run --rm registry-provision
docker compose --env-file .env.stack --profile local up -d --no-deps --force-recreate mosquitto simulator
```

The gateway refreshes both authorized snapshots from the backend every 30 seconds. It verifies each response's digest and assigned tenant and gateway before replacing its persistent cache. A failed refresh keeps the verified snapshot available during network loss. An explicit authentication rejection invalidates that tenant's cache.

## Configure simulated streams

The demo starts one stream per sensor type in each tenant, at one reading per second. Increase `IOT_STACK_SIM_STREAMS_PER_TYPE` to register more streams per type and tenant. Existing registrations are preserved on reprovisioning. Use a separate test dataset to try a lower count. Set `IOT_STACK_SIM_SEED` to reproduce a reading sequence for the same assignment and sample times. Set `IOT_STACK_SIM_MAX_BYTES_PER_TENANT` to adjust the pending Protobuf payload budget, which defaults to five decimal GB per tenant. Account for SQLite overhead and WAL when sizing Docker storage.

The simulator publishes binary telemetry and heartbeats to the private broker. It keeps unacknowledged events across restarts and republishes the same bytes after a receipt timeout. The gateway sends receipts once it durably queues readings or recognizes a queued duplicate.

## Alarms and metrics

Platform operators with local Docker access can inspect the operations plane while the app or TimescaleDB is unavailable. Prometheus, Alertmanager and the receiver have private service addresses. The dashboard is published on loopback.

```sh
python3 scripts/observe.py local rules
python3 scripts/observe.py local alerts
python3 scripts/observe.py local history
python3 scripts/observe.py local export first-soak
python3 scripts/observe.py local watch first-soak
```

`rules` shows pending and firing rules. `alerts` lists Alertmanager groups. `history` reads the SQLite ledger inside the receiver container. `export` writes the past 24 hours of metrics, Docker CPU, memory and container restarts, host disk use and bounded container logs under `run-artifacts/<run-id>/metrics/<UTC-date>/` and the current history at `run-artifacts/<run-id>/alarms.ndjson`. Run `watch` as a laptop process during the soak for one export per UTC day. It retries a missed export when the laptop and Docker are available. The receiver independently records watchdog gaps across laptop sleep or container downtime.

Read live service logs with `docker compose --env-file .env.stack --profile local logs --since 15m <service>`. Java runtime and Nginx access logs use JSON. Container logs rotate at five 20 MB files per service. Keep diagnostic exports outside Git. Keep the independent alarm volume when recreating containers. Confirmed sensor state and operator command audit remain in the tenant-scoped registry.

Prometheus keeps 30 days of metrics subject to the 8 GB size cap. Set `IOT_STACK_METRICS_RETENTION_SIZE` in `.env.stack` after measuring the pilot workload, and record any early eviction in the soak report.

| Alarm stage | Recovery checks |
|---|---|
| Monitoring or broker | Check target health and the latest watchdog timestamp before treating missing metrics as healthy. Inspect Mosquitto and registry ACLs if broker samples stop. |
| Delivery, sensors or ingest | Check MQTT and backend connectivity. Watch the oldest event and per-tenant queue budget during replay. Reconcile any producer drops before closing the incident. |
| Database, trends or controls | Check TimescaleDB, aggregate refresh and the command relay. Verify successful commits and new samples after recovery. |
| Storage or backup | Check Docker and host free space. Reconnect the independent backup target and verify checksums and the last verified backup time. |

A backup job can publish its most recent verified UTC epoch with `python3 scripts/observe.py local backup-status --verified-epoch <epoch>`. Use `--checksum-failed` to report a failed verification while preserving the last verified timestamp. The [local recovery runbook](recovery.md) covers daily backup automation, WAL archival and isolated recovery.

## Inspect the gateway spool

The edge gateway stores original readings in `gateway.sqlite` on its named volume. Set `IOT_STACK_GATEWAY_MAX_BYTES_PER_TENANT` to change the pending payload budget from five decimal GB per tenant. Reserve additional disk space for SQLite tables, indexes and WAL. The gateway exposes per-tenant queue bytes, capacity, oldest age and admission outcomes through its internal Prometheus endpoint.

The gateway uploads a tenant’s queue at 250 readings or when an eligible reading has waited a second. It preserves unconfirmed rows across backend outages and restarts, and it can resume with a verified cached registry while the backend is unreachable. The Java backend validates each entry and commits valid readings and their unique receipt identities in TimescaleDB. The gateway keeps a batch entry until it receives a matching terminal outcome.

## Read browser data

The stack generates separate passwords for `operator-a` and `operator-b` in the owner-only `.env.stack` file. Each demo identity starts with access to one tenant. The browser authentication flow starts with `GET /api/v1/session/csrf`, then uses the returned header and token to submit `POST /api/v1/session`. Keep the same cookie jar across requests. Request a new CSRF token after login or tenant switching. The cookie expires after 30 minutes of inactivity.

Sign in at the dashboard and use the tenant selector when your operator has more than one active membership. Filter the registry by display name or sensor ID. Plot up to ten sensors for a single metric and select the resolution and time range. The effective range is shown in UTC. Expand **View bucket values** to inspect counts, gaps and partial buckets.

Authenticated requests to `GET /api/v1/sensors` and `GET /api/v1/trends` use the active tenant. The versioned contract includes exact query parameters and error responses. [Browser OpenAPI](../contracts/openapi/browser-v1.yaml)

## Trend storage maintenance

Flyway registers two TimescaleDB jobs that run once per minute. The regular job refreshes recent completed quarters and backfills older existing readings in bounded daily windows. The repair job retries historical bucket requests from the durable queue. Keep the TimescaleDB volume across restarts so both the published coverage and pending requests survive outages. The raw readings and receipt ledger retain their data throughout the soak.

## Check services and restart offline

Compose waits for service health before reporting startup complete. Logs rotate at 20 MB with up to five files per container. Java readiness probes and metrics stay on the Docker networks. Nginx serves the dashboard and forwards `/api/` to the Java backend. The backend serves health, metrics, the authenticated internal registry endpoint and the same-origin browser read API.

```sh
docker compose --env-file .env.stack --profile local logs --tail 100
./scripts/stack.sh local offline-up
./scripts/stack.sh local status
```

`offline-up` recreates containers from images already present and forbids building or pulling. The service networks are internal. The dashboard is the sole published endpoint and uses a separate loopback ingress bridge with outbound masquerading disabled. The local project retains database, MQTT, queue and monitoring volumes when containers are recreated. The simulator volume stores its SQLite outbox and sequence checkpoints. The alarm volume stores the durable SQLite ledger. Alertmanager sends authenticated firing and resolved notifications over the private monitoring network.

## Stop, reset or run the test project

```sh
./scripts/stack.sh local down
```

Stopping preserves local volumes. To deliberately erase local stack data, use `./scripts/stack.sh local reset`. Reuse the credential file while restarting an existing volume.

The CI and test profile uses its own project name (`iot-platform-ci`), image tags, generated `.env.test` credentials, and named volumes. Its dashboard port defaults to `18080`.

```sh
./scripts/stack.sh test up
curl --fail http://127.0.0.1:18080/healthz
./scripts/stack.sh test offline-up
./scripts/stack.sh test reset
```

The final command removes the test project's disposable volumes. For test logs, use `docker compose --env-file .env.test --project-name iot-platform-ci --profile test logs --tail 100`.

## Run the short telemetry fault suite

Run this from the repository root after building the test images:

```sh
./scripts/stack.sh test build
python3 scripts/ci/telemetry-faults.py
```

The runner uses a fresh Compose project and deletes its own containers and volumes on exit. It uses the seeded two-tenant fixture and the dedicated `compose.fault.yaml` overlay. The short suite verifies lost MQTT application receipts, simulator silence, fresh broker sessions, gateway and database/backend restarts, binary schema validation, and cross-tenant MQTT publish and delivery denial. It records each generated identity and checks the drained TimescaleDB readings and receipt ledger. The CI smoke job runs the same command and uploads the manifest, case logs and terminal-event list from `build/ci/faults/`.
