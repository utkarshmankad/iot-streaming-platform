# Simulated field and cloud on the Mac

Run the optional profile from the checkout directory.

```sh
./scripts/stack.sh private-link up
./scripts/stack.sh private-link status
./scripts/stack.sh private-link down
```

The dashboard is at [localhost port 18085](http://127.0.0.1:18085). The startup script generates separate local credentials in `.env.private-link` and a short-lived test CA and TLS identities under `run-artifacts/private-link`. The gateway bind-mounts the sibling `storage` directory for its SQLite spool and cached registry. The directory marker and a write probe are checked on every gateway start. Keep this directory between runs to preserve pending readings. `down` keeps the Compose volumes. `reset` removes the profile's Compose volumes. The bind mount and test certificates are host files.

The `iot-platform-private-link_wan` bridge represents the public internet. Only the two uplink proxies are on this bridge. The edge proxy verifies the cloud proxy identity and presents a client certificate. The cloud proxy checks that certificate, forwards only the gateway API routes, and leaves tenant authentication to the backend. MQTT remains on the field network. For a manual drill, disconnect the edge proxy from the WAN bridge, check that the queue grows, and reconnect it. Check both tenants for replay and fresh readings. The CI drill also tests the mount preflight and gateway recreation while disconnected.

For production, deploy the two roles on their respective hosts. Provision a trusted cloud DNS name and server certificate, a separately issued field client certificate, and a process to rotate and revoke credentials. Expose only the authenticated cloud ingress to the internet. Keep the site's broker and the cloud database private. The local profile uses generated fixture credentials and a stand-in directory for the SD card.

## Reconcile the surviving field simulator

Keep the private-link simulator, gateway and broker running during a cloud-only restore. Start the restored cloud backend with `IOT_CONTROL_RESTORE_HOLD=true` and let any in-flight field commands settle or expire. The dashboard can serve reads while controls are held.

```sh
python3 scripts/recovery.py private-link reconcile-field
```

This takes an online snapshot of the surviving simulator, reconciles its sampling state into the restored database, and prints the new generation ID. Check that the confirmed sampling state matches the field device and that the generation floor exceeds the last applied field version before reopening controls. Recreate the restored backend with `IOT_CONTROL_RESTORE_HOLD=false` when verification is complete. Keep using the restored cloud volume during that recreation.

The disposable `python3 scripts/ci/private-link-recovery.py` drill uses a verified PostgreSQL base backup from before a field pause. It restores only the cloud database volume, confirms the survivor is still paused, checks the control hold, delivers a stale command and a late acknowledgment, and verifies that a new enable command applies through the same TLS link. Run it against test images with the private-link Compose project stopped and its disposable named volumes removed.
