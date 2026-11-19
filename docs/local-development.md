# Local development

Clone the repository and start work from the current default branch:

```sh
git clone https://github.com/utkarshmankad/iot-streaming-platform.git
cd iot-streaming-platform
git switch main
git pull --ff-only
git switch -c docs/m0.1-example
```

Install Temurin 25, Node 24.22.2 and npm 11.19.1. Use the checked-in Gradle Wrapper and dependency locks.

```sh
./gradlew build
npm --prefix frontend ci
npm --prefix frontend run build
npm --prefix frontend run dev -- --host 127.0.0.1
```

The Java build produces executable Spring Boot JARs for the backend, gateway and simulator. The frontend build writes to `frontend/dist/`. M0.3 and M0.4 will add the full environment and Compose commands.

## Local configuration

Use an untracked `.env` for service credentials and machine-specific settings. Once the stack defines its configuration, document settings in a checked-in `.env.example` with placeholders. Keep database and queue files in named volumes. Keep host-side backups under `backups/` or on the independent backup target. Store soak outputs under `run-artifacts/` and local runtime logs under `logs/`.

## Pull requests

Use one plan task per branch. Follow the branch naming and Conventional Commit rules in the plan. Include verification in the PR and check off the task when it is delivered. The required GitHub Actions check is `CI gate`.
