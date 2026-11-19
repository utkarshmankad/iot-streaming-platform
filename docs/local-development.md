# Local development

Clone the repository and start work from the current default branch:

```sh
git clone https://github.com/utkarshmankad/iot-streaming-platform.git
cd iot-streaming-platform
git switch main
git pull --ff-only
git switch -c docs/m0.1-example
```

Install Temurin 25, Node 24.22.2, npm 11.19.1, OpenSSL, and Docker with Compose. Use the checked-in Gradle Wrapper and dependency locks. Run the commands from the repository root.

## Build and verify

```sh
npm --prefix frontend ci
./gradlew build
npm --prefix frontend run build
./gradlew spotlessCheck
npm --prefix frontend run typecheck
./gradlew test
./gradlew integrationTest
```

The Java build writes executable Spring Boot JARs under each module’s `build/libs/`. The frontend build writes to `frontend/dist/`. The unit test task will run JUnit tests as modules gain behavior. The integration suite currently starts the backend Spring context. Run `./gradlew spotlessApply` to apply Java formatting.

## Run the development dependencies

```sh
./scripts/init-dev-env.sh
docker compose -f compose.dev.yaml config --quiet
docker compose -f compose.dev.yaml up -d --wait
docker compose -f compose.dev.yaml ps
```

The script generates fresh local database and MQTT passwords in an ignored, owner-only `.env`. It preserves an existing file. `.env.example` lists the supported variables with empty credential placeholders. Keep existing credentials for the lifetime of the development database volume because PostgreSQL uses the password on first initialization.

The standalone development Compose project exposes TimescaleDB on `127.0.0.1:15432` (database and user `iot_dev`) and Mosquitto on `127.0.0.1:11883` (user `iot_dev`). Override the loopback ports in `.env` if they are already in use. The development broker requires its generated password. The deployment stack and per-device topic permissions are covered by subsequent milestones.

## Hot reload and debug

In one terminal, start Vite on loopback for React hot reload:

```sh
npm --prefix frontend run dev -- --host 127.0.0.1
```

In another terminal, launch an individual service with the development profile and attach a local Java debugger to port 5005:

```sh
SPRING_PROFILES_ACTIVE=dev \
SPRING_CONFIG_ADDITIONAL_LOCATION="file:$PWD/config/" \
./gradlew :backend:bootRun --debug-jvm
```

The `dev` profile enables application debug logging. Use `:gateway:bootRun` or `:simulator:bootRun` to debug the other entry points one at a time. The scaffold entry points complete startup and exit after the debugger resumes them. The deployment milestone adds long-running runtime services.

## Stop or reset the development stack

```sh
docker compose -f compose.dev.yaml down
```

This stops the services and preserves the database volume. To explicitly discard development data, run `docker compose -f compose.dev.yaml down --volumes`. Keep host-side backups under `backups/` or on the independent backup target. Store soak outputs under `run-artifacts/` and local runtime logs under `logs/`.

## Pull requests

Use one plan task per branch. Follow the branch naming and Conventional Commit rules in the plan. Include verification in the implementation PR. After it merges, use a separate documentation PR to check off the task in the plan. The required GitHub Actions check is `CI gate`.
