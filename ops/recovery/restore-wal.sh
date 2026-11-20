#!/bin/sh
set -eu

case "$1" in
    ""|*[!A-F0-9.historybackup]*) exit 1 ;;
esac
test -f "/archive/$1.gz"
gzip -dc "/archive/$1.gz" > "$2"
