# One-hour laptop calibration

M4.1 measures the normal MQTT-to-TimescaleDB path in a disposable Compose project. It uses the same two tenants, six sensor types and one-hertz generation as the local stack. The initial run uses 11 streams per type per tenant, or 132 streams. Only the calibration project is stopped for the two queue measurements.

## Run the calibration

From the repository root, prepare the test images and generated `.env.test` credentials with the existing stack script. Use a fresh lowercase run ID. Keep the laptop powered and awake during the measurement.

```sh
./scripts/stack.sh test build
python3 scripts/calibrate.py laptop-calibration
```

The runner starts its own `iot-calibrate-<run-id>` project from cached images. It measures for one hour after both tenants start committing readings. After that window it stops the backend for two minutes to measure gateway spool growth, then stops the gateway for two minutes to measure simulator outbox growth. It restarts each service after the probe and removes its disposable Compose resources when the run ends. Start a new run ID for a retry.

Use `--image-tag` when running prebuilt images under an isolated tag and `--streams-per-type` to repeat the measurement at a different stream count. A shortened run verifies the tool and probes. Use the full default hour to select the pilot workload.

```sh
python3 scripts/calibrate.py calibration-smoke --streams-per-type 1 \
  --seconds 18 --sample-interval 6 --probe-seconds 14
```

## Read the local evidence

Evidence goes in the ignored `run-artifacts/<run-id>/` directory. Keep the measured output outside Git.

| File | What it records |
|---|---|
| `metadata.json` | Source commit, sensor seed, laptop model and architecture, allocated Docker resources, Compose service limits and image IDs. |
| `baseline.json`, `ending.json` | Database snapshot times, committed Protobuf size distribution by tenant and type, SQL versions, relation sizes and WAL positions. |
| `resources.ndjson` | Per-minute Docker resource readings and free space on the host and the Docker volume filesystem. Gaps help identify host sleep or sampling interruptions. |
| `metrics.json` | Prometheus series for rates, errors, queue age and size, ingest latency and available service timings. |
| `footprint.json` | Allocated volume bytes at the start and end, container restart counts, and rendered log bytes over the window. |
| `calibration.json` | Observed throughput, stream recommendation and storage projections, including the measured queue probes. |

The workload calculation counts original Protobuf payload bytes once per committed reading. It reports observed bytes per 24 hours separately from the projected bytes at the recommended one-hertz stream count. The count changes in multiples of 12 so both tenants keep the same number of streams for each type. Check `within_target` and the actual commit rate before using the recommendation in M4.2.

The storage estimates scale observed data, indexes, receipt ledger and aggregate growth to the recommended stream count. Generated WAL comes from the change in PostgreSQL's insert position. WAL currently on disk is measured separately. Each queue probe uses main SQLite file growth plus its observed WAL and applies the OPS-04 capacity margin. The independent backup forecast assumes uncompressed daily bases, database growth for 15 days and 30 days of generated WAL. Use the pilot's measured archive and compression results to refine it. [Timescale relation sizing](https://www.tigerdata.com/docs/reference/timescaledb/hypertables/hypertable_detailed_size) · [PostgreSQL WAL sizing](https://www.postgresql.org/docs/17/functions-admin.html)

Before launching M4.2, compare the projections with measured Docker and host headroom, the OPS-01 planning floor, and the available independent backup target. The full pilot adds an identity journal and runs the longer outage and restore drills. Provision that journal separately from the queue estimates. [Pilot faults](pilot-faults.md) · [Backup and recovery](recovery.md)
