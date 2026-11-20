#!/bin/sh
set -eu

while :; do
  if result=$(mosquitto_sub -h mosquitto -u scaffold_health -P "$MQTT_HEALTH_PASSWORD"     -t '$SYS/broker/clients/connected' -t '$SYS/broker/publish/messages/dropped'     -t '$SYS/broker/messages/received' -C 3 -W 12 -F '%t %p'); then
    {
      date +%s
      printf '%s\n' "$result"
    } > /mosquitto/data/metrics.new
    mv /mosquitto/data/metrics.new /mosquitto/data/metrics
  fi
  sleep 15
done
