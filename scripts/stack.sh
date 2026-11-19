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
        printf '%s\n' 'Usage: scripts/stack.sh {local|test} {up|offline-up|status|down|reset}' >&2
        exit 2
        ;;
esac
profile=$1
action=${2:-}

cd "$(dirname "$0")/.."
case "$action" in
    up)
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
        set -- up --build --wait --wait-timeout 180
        ;;
    offline-up) set -- up --pull never --no-build --force-recreate --wait --wait-timeout 180 ;;
    status) set -- ps ;;
    down) set -- down ;;
    reset) set -- down --volumes ;;
    *)
        printf '%s\n' 'Usage: scripts/stack.sh {local|test} {up|offline-up|status|down|reset}' >&2
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
