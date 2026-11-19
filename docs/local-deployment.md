# Local Docker deployment

Install Docker with Compose and OpenSSL. From the repository root, build and start the stack:

```sh
./scripts/stack.sh local up
./scripts/stack.sh local status
curl --fail http://127.0.0.1:8080/healthz
```

Open the dashboard at `http://127.0.0.1:8080`. Change the published loopback port with `IOT_HTTP_PORT` when needed. The first build downloads pinned images and dependencies. The stack includes separate backend, gateway, simulator and frontend images, plus TimescaleDB, Mosquitto, Prometheus, Alertmanager and the alarm receiver scaffold.

The stack script writes generated database and broker probe passwords to an ignored, owner-only `.env.stack`. Keep this file with the local deployment data volume because PostgreSQL reads the initialization password on first startup. The broker probe has read access only to its health topic. The ingestion milestone adds service and device credentials and topic permissions.

## Check services and restart offline

Compose waits for service health before reporting startup complete. Logs rotate at 20 MB with up to five files per container. Java readiness probes and metrics stay on the Docker networks. Nginx serves the dashboard and forwards `/api/` to the Java backend. The current backend scaffold exposes health and metrics, with application APIs and telemetry clients added in later milestones.

```sh
docker compose --env-file .env.stack --profile local logs --tail 100
./scripts/stack.sh local offline-up
./scripts/stack.sh local status
```

`offline-up` recreates containers from images already present and forbids building or pulling. The service networks are internal. The dashboard is the sole published endpoint and uses a separate loopback ingress bridge with outbound masquerading disabled. The local project retains database, MQTT, queue and monitoring volumes when containers are recreated. The gateway, simulator and alarm volumes reserve storage for later milestones. Alertmanager has an internal webhook target. The scaffold reports unavailable for webhook deliveries until the durable receiver lands.

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
