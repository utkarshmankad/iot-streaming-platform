#!/bin/sh
set -eu
export LC_ALL=C

test "$(cat /archive/.iot-target-id)" = "$IOT_BACKUP_TARGET_ID"
source=$1
name=$2
case "$name" in
    ""|*[!A-F0-9.historybackup]*) exit 1 ;;
esac
system_id=$(pg_controldata "${PGDATA:?}" | awk -F ': *' '/^Database system identifier:/ {print $2}')
case "$system_id" in ""|*[!0-9]*) exit 1 ;; esac
directory=/archive/$system_id
# The mounted drive's parent is private to the host operator.
umask 022
mkdir -p "$directory"
pending=$(mktemp "$directory/.$name.XXXXXX")
trap 'rm -f "$pending"' EXIT HUP INT TERM
gzip -n -c "$source" > "$pending"
chmod 644 "$pending"
sync "$pending"
# Keep an existing segment unless it is exactly the same. A conflicting writer is unsafe.
if ! ln "$pending" "$directory/$name.gz"; then
    cmp -s "$pending" "$directory/$name.gz"
fi
sync "$directory"
