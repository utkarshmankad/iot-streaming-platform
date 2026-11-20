#!/bin/sh
set -eu

case "${1:-}" in
    local)
        project=iot-platform-local
        credentials=.env.stack
        default_port=8080
        ;;
    test)
        project=iot-platform-ci
        credentials=.env.test
        default_port=18080
        ;;
    *)
        printf '%s\n' 'Usage: scripts/stack.sh {local|test} {up|build|up-built|offline-up|status|down|reset}' >&2
        exit 2
        ;;
esac
profile=$1
action=${2:-}

cd "$(dirname "$0")/.."

add_registry_credentials() {
    required='MIGRATE_PASSWORD APP_PASSWORD GATEWAY_A_TOKEN GATEWAY_B_TOKEN
GATEWAY_A_MQTT_PASSWORD GATEWAY_B_MQTT_PASSWORD DEVICE_A_MQTT_PASSWORD DEVICE_B_MQTT_PASSWORD'
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
        printf '%s\n' 'Usage: scripts/stack.sh {local|test} {up|build|up-built|offline-up|status|down|reset}' >&2
        exit 2
        ;;
esac

if [ ! -f "$credentials" ]; then
    printf 'Run scripts/stack.sh %s up to initialize %s.\n' "$profile" "$credentials" >&2
    exit 1
fi

export IOT_HTTP_PORT=${IOT_HTTP_PORT:-$default_port}
export IOT_STACK_IMAGE_TAG=$profile
exec docker compose --env-file "$credentials" --project-name "$project" --profile "$profile" -f compose.yaml "$@"
