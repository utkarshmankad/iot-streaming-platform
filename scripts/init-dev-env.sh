#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
if [ -e .env ] || [ -L .env ]; then
    printf '%s\n' 'Keeping the existing .env.'
    exit 0
fi

db_password=$(openssl rand -hex 24)
mqtt_password=$(openssl rand -hex 24)
umask 077
set -C
while IFS= read -r line; do
    case "$line" in
        IOT_DEV_DB_PASSWORD=) printf 'IOT_DEV_DB_PASSWORD=%s\n' "$db_password" ;;
        IOT_DEV_MQTT_PASSWORD=) printf 'IOT_DEV_MQTT_PASSWORD=%s\n' "$mqtt_password" ;;
        *) printf '%s\n' "$line" ;;
    esac
done < .env.example > .env
printf '%s\n' 'Generated development credentials in .env.'
