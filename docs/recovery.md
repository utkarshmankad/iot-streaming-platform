# Local backup and point-in-time recovery

## Protect the source stack

Mount an encrypted, independently attached filesystem on the laptop and share its path with Docker. Create a private directory on it with mode `0700`. Keep this drive available across internet outages. The host tool checks the filesystem device and the target's generated identity. Encryption comes from the mounted filesystem. The operator is responsible for enabling it and managing its key.

```sh
python3 scripts/recovery.py local configure /Volumes/iot-vault/iot-backups
./scripts/stack.sh local up-built
python3 scripts/recovery.py local take
python3 scripts/recovery.py local watch
```

Use `up` instead of `up-built` for the initial image build. The stack reads the ignored target configuration on each invocation. Enabling archival recreates TimescaleDB in place and keeps its existing Docker volume. PostgreSQL switches WAL within five minutes when there is activity. The archive helper waits for the expected mount identity, writes compressed segments through a temporary file, and publishes each completed segment atomically under its cluster ID. An unavailable or conflicting archive causes PostgreSQL to retain WAL for retry.

`take` runs a physical `pg_basebackup` with streaming WAL and verifies it using the matching PostgreSQL 17 binaries. It snapshots live Gateway and Simulator SQLite databases via the Online Backup API. It records the repository revision, database and extension versions, Flyway revision, per-tenant queue totals, source configuration, component capture times, file sizes and SHA-256 hashes. It creates a named PostgreSQL restore point, waits until its WAL reaches the backup drive, and then publishes the backup manifest. Expect one extra full database copy in the Docker staging area and separate independent space for the compressed base and WAL. Avoid starting a backup under pressure and measure the use against the pilot budget.

The backup directory contains credentials and platform data. Grant operator-only access and keep it outside the source tree. Never attach backup files or their environment file to issues, pull requests or public CI artifacts. The drill artifacts use seeded fixtures and summarize timestamps and counts.

Run `watch` as a laptop process during the soak for one verified backup per UTC day. It retries while the stack or drive is unavailable and resumes after laptop wake. Prometheus and Alertmanager supervise the mounted target, low space, WAL awaiting archival for over 15 minutes, the last verified backup and checksum failures. Inspect alarm history with `python3 scripts/observe.py local history`. The source stack needs continuous access to the target for the 15-minute backend RPO. Independent queue snapshots have their own 24-hour RPO.

Keep at least 30 days of verified backup sets and the complete WAL required by every retained set, through the soak and final verification. Track free space separately from the Docker disk. Review expiry after verifying a newer restore and keeping the last good set. Retain the WAL between the oldest retained base and any target still in scope, including timeline history. The recovery tool leaves expiry decisions with the operator.

## Restore and verify

Use a checkout and local images matching the backup manifest. Use a fresh restore name and a free unprivileged loopback port. Have Docker storage available for the restored database, both queues, and the temporary recovery ledger.

```sh
python3 scripts/recovery.py local verify BACKUP_ID
python3 scripts/recovery.py local restore BACKUP_ID drill-1
python3 scripts/recovery.py local restore BACKUP_ID drill-2 \
  --target-time 2026-11-20T16:00:00Z --port 18083
```

The default target is the backup's named checkpoint. For a timestamp target, pick a UTC instant after the base completed with verified archived WAL to reach it. PostgreSQL fails recovery if the chosen target is unreachable. The restore project uses distinct networks, containers, volumes and a loopback port. The source project and its volumes stay in place. A restore stage that fails preserves its isolated resources for diagnosis. Use a new name for each separate attempt.

The restore holds sensor control writes and MQTT dispatch, validates tenant isolation and checks reading counts against the receipt ledger. It installs the captured SQLite queues into fresh volumes. It clears cached Gateway acknowledgments, opens a new control generation above the restored database and simulator versions, and reconciles desired and confirmed state from the simulator snapshot. Unresolved old commands become superseded or expired. The queryable dashboard is available before the simulator or gateway is started.

Inspect that isolated dashboard and the evidence in `run-artifacts/iot-restore-<name>/restore.json`. Once the recovered data and isolation are verified, use the separate `resume` command to start the restored MQTT and replay workers and reopen controls in the isolated project. The tool reconciles the distinct original queue IDs through the restored receipt ledger. It reports unconfirmed IDs by tenant when a time limit is reached. Reconcile those counts with rejections and metadata before classifying gaps. TimescaleDB uses the existing late-reading repair queue to rebuild affected chart buckets.

```sh
python3 scripts/recovery.py local resume drill-1 --drain-seconds 3600
python3 scripts/recovery.py local measure-rpo drill-1 \
  --incident-at 2026-11-20T16:05:00Z
```

Report the database RPO from the last replayed transaction and report queue RPO separately from each captured snapshot time. Report time until the recovered dashboard was queryable and time until backlog IDs were confirmed separately. Use real incident timestamps for pilot evidence. Independently captured database and queue states can expose gaps. Current tooling restores the simulated laptop stack. Reconnecting a surviving physical site needs its own device-version reconciliation and rollout.

## Cleanup and troubleshooting

Inspect the restore project using its exact Compose name with the saved `stack.env`, the base and recovery Compose files, and `IOT_RESTORE_WAL` set to the manifest cluster's archive directory. Preserve volumes while investigating. When the isolated drill can be discarded, verify the project name begins with `iot-restore-` before using Compose's explicit volume-removal operation. Keep the source project's volumes and the independent backup drive.

If the target is unavailable, reconnect the same encrypted filesystem and confirm its marker and free space. If a checksum fails or WAL is incomplete, use the latest independently verified chain that contains the chosen target. Record the resulting recovery gap before replay. PostgreSQL and SQLite integrity checks are complemented by the CI point-in-time drill. [PostgreSQL continuous archiving](https://www.postgresql.org/docs/17/continuous-archiving.html) · [PostgreSQL backup verification](https://www.postgresql.org/docs/17/app-pgverifybackup.html) · [SQLite Online Backup API](https://www.sqlite.org/backup.html)
