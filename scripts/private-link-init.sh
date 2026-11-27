#!/bin/sh
set -eu

state=$1
storage=$2
umask 077
mkdir -p "$state"
chmod 700 "$state"
if [ ! -e "$state/ready" ]; then
    staging=$(mktemp -d "$state/.certificates.XXXXXX")
    trap 'rm -rf "$staging"' EXIT
    mkdir "$staging/edge" "$staging/cloud"
    openssl req -new -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
        -days 30 -subj '/CN=Local IoT private link CA' -keyout "$staging/ca.key" \
        -out "$staging/ca.pem"
    for role in server client; do
        case "$role" in
            server) name=cloud-uplink; usage=serverAuth; output="$staging/cloud" ;;
            client) name=field-gateway; usage=clientAuth; output="$staging/edge" ;;
        esac
        openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
            -subj "/CN=$name" -keyout "$output/$role.key" -out "$staging/$role.csr"
        printf 'extendedKeyUsage=%s\nsubjectAltName=DNS:%s\n' "$usage" "$name" > "$staging/$role.ext"
        openssl x509 -req -in "$staging/$role.csr" -CA "$staging/ca.pem" -CAkey "$staging/ca.key" \
            -CAcreateserial -days 30 -extfile "$staging/$role.ext" -out "$output/$role.pem"
        cp "$staging/ca.pem" "$output/ca.pem"
    done
    mv "$staging/edge" "$staging/cloud" "$state/"
    mkdir "$storage"
    # The parent directory is private to the Mac user. The container uses UID 10001.
    printf '%s\n' iot-edge-storage-v1 > "$storage/.iot-edge-storage"
    chmod 644 "$storage/.iot-edge-storage"
    chmod 777 "$storage"
    touch "$state/ready"
fi
test -f "$storage/.iot-edge-storage" || {
    printf '%s\n' 'Expected an initialized simulated edge storage directory.' >&2
    exit 1
}
