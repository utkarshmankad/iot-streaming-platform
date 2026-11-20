#!/bin/sh
set -eu
umask 077
export PGPASSWORD="$POSTGRES_PASSWORD"
staging=$(mktemp -d /tmp/iot-base.XXXXXX)
trap 'rm -rf "$staging"' EXIT HUP INT TERM
pg_basebackup --host=127.0.0.1 --username="$POSTGRES_USER" --no-password \
    --pgdata="$staging" --format=plain --wal-method=stream \
    --checkpoint=fast --manifest-checksums=SHA256
pg_verifybackup --exit-on-error "$staging" >&2
tar -czf - -C "$staging" .
