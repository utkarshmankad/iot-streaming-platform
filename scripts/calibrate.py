#!/usr/bin/env python3
"""Measure a disposable local Compose workload and estimate pilot storage."""

import argparse
import json
import math
import os
import platform
import re
import shutil
import subprocess
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode

ROOT = Path(__file__).resolve().parents[1]
TARGET_BYTES = 1_000_000_000
DAY_SECONDS = 86_400
SEED = "20260301"
VOLUME_PATHS = {
    "timescaledb": "/var/lib/postgresql/data",
    "gateway": "/var/lib/iot",
    "simulator": "/var/lib/iot",
    "mosquitto": "/mosquitto/data",
    "prometheus": "/prometheus",
    "alertmanager": "/alertmanager",
    "alarm-receiver": "/var/lib/iot/alarms",
}

DATABASE_SAMPLE = """
SELECT json_build_object(
    'at', extract(epoch FROM statement_timestamp()),
    'payload', (SELECT coalesce(json_agg(p ORDER BY tenant, type, bytes), '[]') FROM (
        SELECT r.tenant_id AS tenant, s.type_code AS type, r.payload_bytes AS bytes,
               count(*) AS samples
        FROM telemetry.readings r JOIN registry.sensors s USING (tenant_id, sensor_id)
        GROUP BY r.tenant_id, s.type_code, r.payload_bytes
    ) p),
    'streams', (SELECT json_agg(s ORDER BY tenant) FROM (
        SELECT tenant_id AS tenant, count(*) AS enabled, count(DISTINCT type_code) AS types
        FROM registry.sensors WHERE status='active' AND sampling_period_ms=1000 GROUP BY tenant_id
    ) s),
    'storage', json_build_object(
        'raw_heap', (SELECT table_bytes FROM public.hypertable_detailed_size('telemetry.readings')),
        'raw_indexes', (SELECT index_bytes FROM public.hypertable_detailed_size('telemetry.readings')),
        'receipt_heap', pg_table_size('telemetry.event_receipts'),
        'receipt_indexes', pg_indexes_size('telemetry.event_receipts'),
        'aggregate', public.hypertable_size('telemetry.sensor_15m'),
        'database', pg_database_size(current_database()),
        'wal_on_disk', (SELECT coalesce(sum(size),0) FROM pg_ls_waldir()),
        'wal_generated', pg_wal_lsn_diff(pg_current_wal_insert_lsn(), '0/0')
    ),
    'versions', json_build_object('postgres', current_setting('server_version'),
        'timescale', (SELECT extversion FROM pg_extension WHERE extname='timescaledb'))
)
"""


def command(*args, env=None, timeout=60):
    return subprocess.run(args, cwd=ROOT, env=env, check=True, text=True,
                          stdout=subprocess.PIPE, timeout=timeout).stdout.strip()


def save(path, data):
    partial = path.with_suffix(path.suffix + ".tmp")
    partial.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    partial.replace(path)


def utc(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat()


class Stack:
    def __init__(self, run_id, tag, streams):
        self.project = f"iot-calibrate-{run_id}"
        self.environment = dict(os.environ, IOT_HTTP_PORT="0", IOT_STACK_IMAGE_TAG=tag,
                                IOT_STACK_SIM_STREAMS_PER_TYPE=str(streams), IOT_STACK_SIM_SEED=SEED)
        self.args = ("docker", "compose", "--env-file", ".env.test", "--project-name",
                     self.project, "-f", "compose.yaml", "--profile", "test")

    def run(self, *args, timeout=60):
        return command(*self.args, *args, env=self.environment, timeout=timeout)

    def up(self, *services):
        self.run("up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180",
                 *services, timeout=200)

    def execute(self, service, *args):
        return self.run("exec", "-T", service, *args)

    def db(self):
        return json.loads(self.execute("timescaledb", "psql", "-XqAt", "-U", "iot", "-d", "iot",
                                       "-v", "ON_ERROR_STOP=1", "-c", DATABASE_SAMPLE))

    def metadata(self):
        config = json.loads(self.run("config", "--format", "json"))["services"]
        limits = {name: {k: service[k] for k in ("image", "cpus", "mem_limit", "mem_reservation")
                         if k in service} for name, service in config.items()}
        images = sorted({data["image"] for data in limits.values()})
        docker = json.loads(command("docker", "info", "--format",
                                    '{"cpus":{{.NCPU}},"ram_bytes":{{.MemTotal}},"architecture":{{json .Architecture}},"version":{{json .ServerVersion}}}'))
        host = {"os": platform.system(), "release": platform.release(), "arch": platform.machine(),
                "logical_cpus": os.cpu_count()}
        if host["os"] == "Darwin":
            host["model"] = command("sysctl", "-n", "hw.model")
            host["ram_bytes"] = int(command("sysctl", "-n", "hw.memsize"))
        elif host["os"] == "Linux":
            host["ram_bytes"] = os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
        return {"source_commit": command("git", "rev-parse", "HEAD"), "project": self.project,
                "seed": int(SEED), "host": host, "docker": docker,
                "compose_version": command("docker", "compose", "version", "--short"), "services": limits,
                "images": command("docker", "image", "inspect", "--format",
                                  "{{json .Id}} {{json .RepoTags}} {{json .Architecture}}", *images).splitlines()}

    def resources(self):
        ids = self.run("ps", "--status", "running", "-q").splitlines()
        stats = command("docker", "stats", "--no-stream", "--format", "{{json .}}", *ids)
        disk = self.execute("timescaledb", "df", "-Pk", "/var/lib/postgresql/data")
        return {"at": time.time(), "host_free_bytes": shutil.disk_usage(ROOT).free,
                "docker_free_bytes": int(disk.splitlines()[-1].split()[3]) * 1024,
                "containers": list(map(json.loads, stats.splitlines()))}

    def footprint(self):
        sizes = {service: int(self.execute(service, "du", "-sk", path).split()[0]) * 1024
                 for service, path in VOLUME_PATHS.items()}
        ids = self.run("ps", "--status", "running", "-q").splitlines()
        restarts = command("docker", "inspect", "--format", "{{.Name}} {{.RestartCount}}", *ids)
        return {"at": time.time(), "volume_bytes": sizes,
                "restart_counts": {name: int(count) for name, count in
                                   (line.split() for line in restarts.splitlines())}}

    def log_bytes(self, start):
        logs = self.run("logs", "--since", utc(start), "--no-color")
        return len(logs.encode("utf-8"))

    def queue(self, service, metric, filename):
        exposition = self.execute(service, "wget", "-qO-", "http://127.0.0.1:8080/actuator/prometheus")
        events = [int(float(value)) for value in re.findall(rf"^{metric}\{{[^\n]*\}} (\S+)$",
                                                           exposition, re.MULTILINE)]
        sizes = self.execute(service, "sh", "-ec", '''
            for path in "$1" "$1-wal"; do
                if [ -f "$path" ]; then stat -c %s "$path"; else printf '0\n'; fi
            done
            ''', "queue", f"/var/lib/iot/{filename}").splitlines()
        if len(events) < 2:
            raise ValueError(f"Missing per-tenant queue metrics for {service}")
        return {"events": sum(events), "sqlite_bytes": int(sizes[0]), "wal_bytes": int(sizes[1])}

    def queue_probe(self, blocked, service, metric, filename, seconds):
        before = self.queue(service, metric, filename)
        self.run("stop", blocked)
        try:
            wait_until(time.monotonic() + seconds)
            after = self.queue(service, metric, filename)
        finally:
            self.up(blocked)
        if after["events"] <= before["events"]:
            raise ValueError(f"No backlog accumulated for the {service} queue probe")
        return {"before": before, "after": after, "outage_seconds": seconds}

    def prometheus(self, start, end):
        query = '{__name__=~"up|iot_.*|http_server_requests_seconds_.*|process_cpu_usage|jvm_memory_used_bytes"}'
        path = urlencode({"query": query, "start": start, "end": end, "step": "60s"})
        result = json.loads(self.execute("prometheus", "wget", "-qO-",
                                        "http://127.0.0.1:9090/api/v1/query_range?" + path))
        if result.get("status") != "success":
            raise ValueError("Prometheus did not return the calibration series")
        return result


def wait_until(deadline):
    while (remaining := deadline - time.monotonic()) > 0:
        time.sleep(min(remaining, 30))


def measure(stack, folder, seconds, interval):
    startup_deadline = time.monotonic() + 90
    baseline = stack.db()
    while len({row["tenant"] for row in baseline["payload"]}) < 2:
        if time.monotonic() >= startup_deadline:
            raise ValueError("Telemetry did not reach both tenants")
        time.sleep(1)
        baseline = stack.db()
    save(folder / "baseline.json", baseline)
    started = time.monotonic()
    print(f"Sampling started at {utc(baseline['at'])}", flush=True)
    footprint = stack.footprint()
    with (folder / "resources.ndjson").open("w", encoding="utf-8") as output:
        for offset in range(0, seconds, interval):
            wait_until(started + offset)
            output.write(json.dumps(stack.resources()) + "\n")
            output.flush()
    wait_until(started + seconds)
    ending = stack.db()
    save(folder / "ending.json", ending)
    save(folder / "metrics.json", stack.prometheus(baseline["at"], ending["at"]))
    save(folder / "footprint.json", {"before": footprint, "after": stack.footprint(),
                                     "rendered_log_bytes": stack.log_bytes(baseline["at"])})
    return baseline, ending


def recommendation(before, after, probes, resources):
    def histogram(snapshot):
        return Counter({(row["tenant"], row["type"], row["bytes"]): row["samples"]
                        for row in snapshot["payload"]})

    all_readings, initial = histogram(after), histogram(before)
    if not initial <= all_readings:
        raise ValueError("A committed payload group lost readings during measurement")
    measured = all_readings - initial
    counts = {"readings": measured.total(), "bytes": sum(size * count for (_, _, size), count in measured.items())}
    if not counts["readings"] or len(after["streams"]) < 2:
        raise ValueError("No usable multi-tenant sample")
    mean = counts["bytes"] / counts["readings"]
    group = sum(row["types"] for row in after["streams"])
    n = max(1, min(1000, round(TARGET_BYTES / (group * mean * DAY_SECONDS))))
    streams = n * group
    daily_payload = round(streams * mean * DAY_SECONDS)
    elapsed = after["at"] - before["at"]
    scale = streams * DAY_SECONDS / counts["readings"]
    growth = {name: after["storage"][name] - before["storage"][name] for name in after["storage"]}
    daily_growth = {name: math.ceil(max(0, value) * scale) for name, value in growth.items()
                    if name != "wal_on_disk"}
    queues = {}
    for service, sample in probes.items():
        start, end = sample["before"], sample["after"]
        incremental = max(0, end["sqlite_bytes"] - start["sqlite_bytes"])
        per_event = max(mean, incremental / (end["events"] - start["events"]))
        reserve = math.ceil(1.3 * (per_event * streams * DAY_SECONDS * 7 + end["wal_bytes"]))
        queues[service] = {"estimated_bytes_per_queued_event": per_event,
                           "required_usable_bytes": max(10_000_000_000, reserve), **sample}
    daily_db = daily_growth["database"]
    wal_day = daily_growth["wal_generated"]
    db_base = before["storage"]["database"]
    db_at_soak_end = db_base + daily_db * 15
    return {"start": utc(before["at"]), "end": utc(after["at"]), "sample_seconds": elapsed,
            "committed": counts, "observed_bytes_per_24h": round(counts["bytes"] / elapsed * DAY_SECONDS),
            "mean_payload_bytes": mean, "payload_histogram": [
                {"tenant": tenant, "type": kind, "bytes": size, "readings": total}
                for (tenant, kind, size), total in sorted(measured.items())],
            "recommended_streams_per_type_per_tenant": n, "recommended_streams": streams,
            "projected_payload_bytes_per_24h": daily_payload,
            "within_target": 0.95 * TARGET_BYTES <= daily_payload <= 1.05 * TARGET_BYTES,
            "storage_delta_bytes": growth, "daily_growth_bytes": daily_growth,
            "database_at_day_15_bytes": db_at_soak_end, "queues": queues,
            "uncompressed_30_day_backup_bytes": (30 * db_base + (sum(range(1, 16)) + 15 * 15) * daily_db
                                                 + 30 * wal_day),
            "host_free_bytes": resources["host_free_bytes"], "docker_free_bytes": resources["docker_free_bytes"]}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_id")
    parser.add_argument("--image-tag", default="test")
    parser.add_argument("--streams-per-type", default=11, type=int)
    parser.add_argument("--seconds", default=3600, type=int)
    parser.add_argument("--sample-interval", default=60, type=int)
    parser.add_argument("--probe-seconds", default=120, type=int)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,43}", args.run_id):
        parser.error("Use a lowercase run ID of up to 44 letters, digits and hyphens.")
    if not 1 <= args.streams_per_type <= 1000 or min(args.seconds, args.sample_interval, args.probe_seconds) < 1:
        parser.error("Use positive durations and between 1 and 1000 streams per type.")
    stack = Stack(args.run_id, args.image_tag, args.streams_per_type)
    if (command("docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={stack.project}")
            or command("docker", "volume", "ls", "-q", "--filter", f"label=com.docker.compose.project={stack.project}")):
        parser.error("Choose a fresh calibration run ID.")
    folder = ROOT / "run-artifacts" / args.run_id
    folder.mkdir(parents=True, exist_ok=False)
    save(folder / "metadata.json", stack.metadata())
    try:
        stack.up()
        before, after = measure(stack, folder, args.seconds, args.sample_interval)
        probes = {
            "gateway": stack.queue_probe("backend", "gateway", "iot_gateway_spool_events",
                                          "gateway.sqlite", args.probe_seconds),
            "simulator": stack.queue_probe("gateway", "simulator", "iot_simulator_pending_events",
                                            "simulator.sqlite", args.probe_seconds),
        }
        report = recommendation(before, after, probes, stack.resources())
        save(folder / "calibration.json", report)
        print(folder / "calibration.json", flush=True)
    finally:
        stack.run("down", "--volumes", "--remove-orphans", timeout=180)


if __name__ == "__main__":
    main()
