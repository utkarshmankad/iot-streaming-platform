# Pilot fault drills

Run FT-01G through FT-01J against a fresh synthetic Compose project on the laptop. Use the pilot profile to get two tenants, 11 one-hertz streams per sensor type and tenant by default, and a durable simulator identity journal. It uses the same service images, rule thresholds and network paths as the local stack. Allow disk space for the journal and reconciliation exports in addition to the OPS-04 queue budget. Keep pilot data and evidence outside Git.

## Start and record a case

The pilot uses the disposable `iot-platform-ci` project so the existing `test` monitoring and recovery commands can inspect it. From the repository root, check `docker compose ls --all` and `docker volume ls --filter label=com.docker.compose.project=iot-platform-ci` for any previous test or pilot state. Start with fresh volumes and choose a unique evidence directory. The normal laptop deployment lives in `iot-platform-local`. For the steady pilot and FT-01G, use the M4.1 calibrated setting of 10 streams per type per tenant and seed `20260301`.

```sh
IOT_STACK_SIM_STREAMS_PER_TYPE=10 IOT_STACK_SIM_SEED=20260301 ./scripts/stack.sh pilot up
./scripts/stack.sh pilot status
```

Use `pilot up-built` to start from existing test images. To change the generated stream count or queue budget for a case, set `IOT_STACK_SIM_STREAMS_PER_TYPE`, `IOT_STACK_GATEWAY_MAX_BYTES_PER_TENANT` or `IOT_STACK_SIM_MAX_BYTES_PER_TENANT` before starting its fresh project. Read the dashboard at `http://127.0.0.1:18080` unless `IOT_HTTP_PORT` was set.

For each case, record the source revision, seed, per-tenant rate and payload size, queue budgets, fault start, first warning and critical notification, restoration and drain time in UTC. Capture each phase in a separate directory under `run-artifacts/<run-id>/fault-tests/<case-id>/`. Preserve a baseline before injection and a final sample after the queues drain. Use the two operator sessions to check trend data and command state and export the relevant sensor and bucket values. [Local deployment and observability](local-deployment.md)

```sh
run_id=pilot-01
case_id=FT-01G
phase=before
evidence="run-artifacts/$run_id/fault-tests/$case_id/$phase"
mkdir -p "$evidence"
date -u +%Y-%m-%dT%H:%M:%SZ >"$evidence/time.txt"
python3 scripts/observe.py test rules >"$evidence/rules.json"
python3 scripts/observe.py test alerts >"$evidence/alerts.json"
python3 scripts/observe.py test history >"$evidence/alarms.ndjson"
python3 scripts/observe.py test export "$run_id-$case_id-$phase"

pilot_compose() {
    IOT_STACK_IMAGE_TAG=test docker compose --env-file .env.test --profile test \
        --project-name iot-platform-ci -f compose.yaml -f compose.pilot-fault.yaml "$@"
}
pilot_compose logs --no-color --since 15m >"$evidence/compose.log"
```

The rules and metrics exports show queue depth and age, scrape status and alert timestamps. When a service is unreachable, record which evidence could be collected and retrieve the persisted alarm events after recovery.

## Outages and recovery

**FT-01G · WAN and gateway-to-backend partition.** With images cached, disconnect Internet access while keeping local Docker networking available. Confirm that both tenants still ingest data, show fresh trends and complete a pause/resume command. Restore Internet access. To isolate backend delivery while preserving MQTT, run the following against the pilot project:

```sh
gateway=$(pilot_compose ps -q gateway)
test -n "$gateway"
docker network disconnect iot-platform-ci_gateway-api "$gateway"
# After 24 active hours of generation, capture peak backlog, age and alerts before reconnecting.
docker network connect --alias gateway iot-platform-ci_gateway-api "$gateway"
```

Run this partition separately from the 24-hour steady measurement. Accumulate 24 hours with the calibrated sensor fleet generating readings and MQTT delivering them to the gateway. Capture the link cut and restoration in UTC. Sample queue depth, bytes, oldest age and available disk during active time. Size each tested queue from the measured 24-hour on-disk growth plus 30% margin and confirm that replay and the simulator journal also fit. At 120 one-hertz streams, 24 active hours is approximately 10.37 million readings or 0.975 GB of original payloads. Compare distinct counts and payload bytes by tenant with observations. After reconnect, keep live traffic running, measure commit and drain rates and elapsed drain time by tenant, and check that affected historical buckets repair. Reconcile accepted and committed events, record any gaps and check that backlog alerts resolve. [FT-01G architecture](../architecture.md#9-fault-injection-test-plan)

Keep the partition and Docker volumes in place during travel sleep. Record the actual sleep and wake timestamps from the Mac power log (`pmset -g log`) and check the gateway network membership and queue after wake. Reconnect after the full active window, with the sleep interval excluded. Record elapsed and active durations separately and compare the active time against the sample timeline and per-tenant generated counts. Investigate gaps outside the documented sleep interval before accepting the run. Preserve the original runner summary and attach the active-time assessment if its continuous-window check flags sleep.

**FT-01H · Tenant quota and storage limit.** Start a fresh profile with one stream per metric and tenant. Set a small gateway byte quota and a smaller simulator queue budget. Pause five tenant B streams using its operator account before disconnecting the gateway/backend network. Keep the sixth B stream enabled. At a measured payload size near 90 bytes, a 1,000,000-byte limit gives tenant A's six one-hertz streams enough time to reach 70% for five minutes before the queue is full. Adjust the quota against the measured rate. Record A's warning, critical and full events, its producer drop count if the outbox is exhausted, and B's remaining capacity and receipts. Repeat with a separately bounded scratch filesystem for gateway storage if available. Compare storage errors, spool survival and backpressure with the per-tenant quota case. Reconnect, confirm alarms resolve and reconcile intentional drops against the journal.

**FT-01I · Backup and archive failure.** Configure the `test` recovery tooling against an independently mounted encrypted pilot target. Recreate the pilot with `./scripts/stack.sh pilot up-built` and take a verified backup. Temporarily disconnect the pilot target or interrupt its archival and confirm that pending WAL triggers the critical `WalArchiveOverdue` alarm. To exercise an overdue backup during a shorter pilot, publish a timestamp 27 hours old with `python3 scripts/observe.py test backup-status --verified-epoch "$(( $(date -u +%s) - 97200 ))"` and label the simulated age in the evidence. Test checksum rejection on a disposable copy. Reconnect the target, take a fresh verified backup to clear the signal and restore to a new project. Validate tenant boundaries and the command hold, then resume replay. Record actual backend and queue RPO separately, time to reach a queryable dashboard and time until the replay backlog clears. [Backup and restore commands](recovery.md)

**FT-01I Google Drive option.** Use a separate synthetic test project and a private, Docker-accessible local staging directory. Keep the staging directory outside Google Drive sync. Configure the test profile there, take a verified backup, and retain every completed WAL segment required to reach the chosen recovery point. Interrupt local archival and verify firing and recovery of the overdue alarms on that test project. On a disposable copy, confirm that a checksum failure prevents restore.

Package the verified backup directory, its manifest and the required WAL with their original paths. Encrypt the bundle locally. Keep the decryption key separately and upload only the encrypted image to a restricted Drive folder. Download a fresh copy, compare its SHA-256 with the original encrypted image, and unpack into a new private staging directory. Configure the downloaded archive in a separate restore checkout or after the source backup checks finish. Verify the manifest, then restore to fresh volumes. Record Drive transfer time and availability separately from queryable restore time, replay time and RPO. The local WAL alarm tracks local archival. Recovery from the cloud copy requires internet access.

**FT-01J · Monitoring loss and sleep.** Stop the pilot alarm receiver or a monitored service and capture the last good scrape or watchdog delivery. Restore it and check firing and resolved history. Record the sleep and wake times during one laptop sleep interval and confirm the receiver reports the watchdog gap after evaluation resumes. Compare the recorded interval with the pilot timeline. The sleep during FT-01G can satisfy this check when the power log and watchdog evidence agree.

## Reconcile a drained pilot

After measuring recovery under live load, pause new readings and leave the simulator running to receive durable receipts. Check both tenants' `iot_simulator_pending_events` and `iot_gateway_spool_events` counters until they reach zero and retain the metrics that show both queues drained. Stop the simulator and gateway, then copy the closed simulator database and export the two PostgreSQL identity sets in order. Run these commands while still in the pilot project and keep the evidence with its case.

```sh
pilot_compose exec -T simulator touch /var/lib/iot/fault-silence
# Wait for both queues to reach zero before stopping either container.
pilot_compose stop -t 30 simulator gateway
pilot_compose cp simulator:/var/lib/iot/simulator.sqlite "$evidence/simulator.sqlite"
pilot_compose exec -T timescaledb psql -XqAt -U iot -d iot -v ON_ERROR_STOP=1 \
    -c 'COPY (SELECT tenant_id,sensor_id,event_id,(extract(epoch FROM observed_at)*1000)::bigint FROM telemetry.readings ORDER BY tenant_id,sensor_id,event_id) TO STDOUT WITH CSV' \
    >"$evidence/readings.csv"
pilot_compose exec -T timescaledb psql -XqAt -U iot -d iot -v ON_ERROR_STOP=1 \
    -c 'COPY (SELECT tenant_id,sensor_id,event_id FROM telemetry.event_receipts ORDER BY tenant_id,sensor_id,event_id) TO STDOUT WITH CSV' \
    >"$evidence/receipts.csv"
python3 scripts/pilot-reconcile.py "$evidence/simulator.sqlite" \
    "$evidence/readings.csv" "$evidence/receipts.csv" >"$evidence/reconciliation.json"
```

The comparison streams identities in order and fails on any difference in identity, observation time, receipt ledger or simulator outcomes. If the stopped simulator still has a `simulator.sqlite-wal` file, copy it next to the database before running the comparison. Reconcile only the stopped snapshot and keep it and its copied WAL fixed while the command runs. A nonempty WAL requires temporary writable space for a copy of the database and WAL. The original evidence remains read-only. Record expected producer drops and rejections by tenant, with their injection interval and expected reason. Keep evidence before using `./scripts/stack.sh pilot down`. Only reset verified disposable volumes when the evidence has been captured and reviewed.
