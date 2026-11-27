#!/bin/sh
set -eu

case "${1:-}" in
    local)
        project=iot-platform-local
        credentials=.env.stack
        default_port=8080
        ;;
    test|pilot)
        project=iot-platform-ci
        credentials=.env.test
        default_port=18080
        ;;
    private-link)
        project=iot-platform-private-link
        credentials=.env.private-link
        default_port=18085
        ;;
    *)
        printf '%s\n' 'Usage: scripts/stack.sh {local|test|pilot|private-link} {up|build|up-built|offline-up|status|down|reset}' >&2
        exit 2
        ;;
esac
profile=$1
deployment=$profile
case "$deployment" in
    pilot|private-link) profile=test ;;
esac
action=${2:-}

cd "$(dirname "$0")/.."

add_registry_credentials() {
    required='MIGRATE_PASSWORD APP_PASSWORD GATEWAY_A_TOKEN GATEWAY_B_TOKEN
GATEWAY_A_MQTT_PASSWORD GATEWAY_B_MQTT_PASSWORD DEVICE_A_MQTT_PASSWORD DEVICE_B_MQTT_PASSWORD OPERATOR_A_PASSWORD OPERATOR_B_PASSWORD ALARM_WEBHOOK_TOKEN'
    # Keep existing deployment passwords and append only missing registry values.
    additions=$(
        for name in $required; do
            key=IOT_STACK_$name
            if ! grep -q "^$key=" "$credentials"; then
                value=$(openssl rand -hex 24) || exit 1
                printf '%s=%s\n' "$key" "$value"
            fi
        done
    )
    if [ -n "$additions" ]; then
        printf '\n%s\n' "$additions" >> "$credentials"
    fi
}
case "$action" in
    up|build|up-built)
        if [ ! -e "$credentials" ] && [ ! -L "$credentials" ]; then
            db_password=$(openssl rand -hex 24)
            mqtt_password=$(openssl rand -hex 24)
            (
                umask 077
                set -C
                printf 'IOT_STACK_DB_PASSWORD=%s\nIOT_STACK_MQTT_HEALTH_PASSWORD=%s\n' \
                    "$db_password" "$mqtt_password" > "$credentials"
            )
        fi
        add_registry_credentials
        case "$action" in
            up) set -- up --build --wait --wait-timeout 180 ;;
            build) set -- build ;;
            up-built) set -- up --no-build --wait --wait-timeout 180 ;;
        esac
        ;;
    offline-up)
        if [ -f "$credentials" ]; then add_registry_credentials; fi
        set -- up --pull never --no-build --force-recreate --wait --wait-timeout 180
        ;;
    status) set -- ps ;;
    down) set -- down ;;
    reset) set -- down --volumes ;;
    *)
        printf '%s\n' 'Usage: scripts/stack.sh {local|test|pilot|private-link} {up|build|up-built|offline-up|status|down|reset}' >&2
        exit 2
        ;;
esac

if [ ! -f "$credentials" ]; then
    printf 'Run scripts/stack.sh %s up to initialize %s.\n' "$deployment" "$credentials" >&2
    exit 1
fi

if [ "$action" = up ] || [ "$action" = build ]; then
    # The classic builder cannot share work between parallel Compose builds.
    if [ "${DOCKER_BUILDKIT:-1}" = 0 ] || ! docker buildx version >/dev/null 2>&1; then
        export COMPOSE_PARALLEL_LIMIT=1
    fi
fi

export IOT_HTTP_PORT=${IOT_HTTP_PORT:-$default_port}
export IOT_STACK_IMAGE_TAG=$profile
backup_config=.env.backup-$profile
if [ "$deployment" != private-link ] && [ -f "$backup_config" ]; then
    IOT_BACKUP_DIR=$(sed -n '1p' "$backup_config")
    IOT_BACKUP_TARGET_ID=$(sed -n '2p' "$backup_config")
    export IOT_BACKUP_DIR IOT_BACKUP_TARGET_ID
    set -- -f compose.backup.yaml "$@"
fi
if [ "$deployment" = pilot ]; then set -- -f compose.pilot-fault.yaml "$@"; fi
if [ "$deployment" = private-link ]; then
    IOT_PRIVATE_LINK_STATE=${IOT_PRIVATE_LINK_STATE:-"$PWD/run-artifacts/private-link"}
    case "$IOT_PRIVATE_LINK_STATE" in
        /*) ;;
        *) printf '%s\n' 'Use an absolute path for IOT_PRIVATE_LINK_STATE.' >&2; exit 1 ;;
    esac
    IOT_EDGE_STORAGE="$IOT_PRIVATE_LINK_STATE/storage"
    IOT_STACK_IMAGE_TAG=${IOT_PRIVATE_LINK_IMAGE_TAG:-test}
    export IOT_PRIVATE_LINK_STATE IOT_EDGE_STORAGE
    case "$action" in
        up|up-built|offline-up) sh scripts/private-link-init.sh "$IOT_PRIVATE_LINK_STATE" "$IOT_EDGE_STORAGE" ;;
    esac
    set -- -f compose.private-link.yaml "$@"
fi
exec docker compose --env-file "$credentials" --project-name "$project" --profile "$profile" -f compose.yaml "$@"
