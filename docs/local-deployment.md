# Local Docker deployment

Install Docker with Compose and OpenSSL. From the repository root, build and start the stack:

```sh
./scripts/stack.sh local up
./scripts/stack.sh local status
curl --fail http://127.0.0.1:8080/healthz
```

Open the dashboard at `http://127.0.0.1:8080`. Change the published loopback port with `IOT_HTTP_PORT` when needed. The first build downloads pinned images and dependencies. The stack includes separate backend, gateway, simulator and frontend images, plus TimescaleDB, Mosquitto, Prometheus, Alertmanager and the alarm receiver scaffold.

The stack script writes generated database, broker and gateway credentials to an ignored, owner-only `.env.stack`. It adds missing registry credentials to existing files without replacing their database or broker probe passwords. Keep this file with the local deployment data volume because PostgreSQL reads the initialization password on first startup.

Two one-shot Compose jobs create the separate database roles and run the Flyway registry migrations. The demo provisioner registers two tenants with overlapping sensor IDs and produces an MQTT ACL and a simulator assignment file from their active assignments. Repeated provisioning preserves existing registry rows and status changes. The backend uses the restricted runtime role and the broker loads separate credentials and topic rules for the two gateways and two devices.

To refresh the projections after local registry changes, run the provisioner again and recreate the broker and simulator so both load the new assignments.

```sh
docker compose --env-file .env.stack --profile local run --rm registry-provision
docker compose --env-file .env.stack --profile local up -d --no-deps --force-recreate mosquitto simulator
```

The gateway refreshes both authorized snapshots from the backend every 30 seconds. It verifies each response's digest and assigned tenant and gateway before replacing its persistent cache. A failed refresh keeps the verified snapshot available during network loss. An explicit authentication rejection invalidates that tenant's cache.

## Configure simulated streams

The demo starts one stream per sensor type in each tenant, at one reading per second. Increase `IOT_STACK_SIM_STREAMS_PER_TYPE` to register more streams per type and tenant. Existing registrations are preserved on reprovisioning. Use a separate test dataset to try a lower count. Set `IOT_STACK_SIM_SEED` to reproduce a reading sequence for the same assignment and sample times. Set `IOT_STACK_SIM_MAX_BYTES_PER_TENANT` to adjust the pending Protobuf payload budget, which defaults to five decimal GB per tenant. Account for SQLite overhead and WAL when sizing Docker storage.

The simulator publishes binary telemetry and heartbeats to the private broker. It keeps unacknowledged events across restarts and republishes the same bytes after a receipt timeout. The gateway sends receipts once it durably queues readings or recognizes a queued duplicate.

## Inspect the gateway spool

The edge gateway stores original readings in `gateway.sqlite` on its named volume. Set `IOT_STACK_GATEWAY_MAX_BYTES_PER_TENANT` to change the pending payload budget from five decimal GB per tenant. Reserve additional disk space for SQLite tables, indexes and WAL. The gateway exposes per-tenant queue bytes, capacity, oldest age and admission outcomes through its internal Prometheus endpoint.

The gateway uploads a tenant’s queue at 250 readings or when an eligible reading has waited a second. It preserves unconfirmed rows across backend outages and restarts, and it can resume with a verified cached registry while the backend is unreachable. The demo backend will begin committing these batches in M1.4. Until then the gateway retains accepted readings and retries delivery with backoff.

## Check services and restart offline

Compose waits for service health before reporting startup complete. Logs rotate at 20 MB with up to five files per container. Java readiness probes and metrics stay on the Docker networks. Nginx serves the dashboard and forwards `/api/` to the Java backend. The backend serves health and metrics and has an authenticated internal registry endpoint. Browser APIs arrive in later milestones.

```sh
docker compose --env-file .env.stack --profile local logs --tail 100
./scripts/stack.sh local offline-up
./scripts/stack.sh local status
```

`offline-up` recreates containers from images already present and forbids building or pulling. The service networks are internal. The dashboard is the sole published endpoint and uses a separate loopback ingress bridge with outbound masquerading disabled. The local project retains database, MQTT, queue and monitoring volumes when containers are recreated. The simulator volume stores its SQLite outbox and sequence checkpoints. The alarm volume reserves storage for a later milestone. Alertmanager has an internal webhook target. The scaffold reports unavailable for webhook deliveries until the durable receiver lands.

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
