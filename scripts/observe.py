#!/usr/bin/env python3
"""Inspect the local operations plane or export a UTC day of soak evidence."""
import argparse
import csv
import io
import json
import os
import re
import shutil
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode

ROOT = Path(__file__).resolve().parents[1]


def compose(profile, *args, payload=None):
    testing = profile == "test"
    project = "iot-platform-ci" if testing else "iot-platform-local"
    envfile = ".env.test" if testing else ".env.stack"
    return subprocess.run(
        ["docker", "compose", "--env-file", envfile, "-p", project, "--profile", profile, *args],
        cwd=ROOT, env=dict(os.environ, IOT_STACK_IMAGE_TAG=profile), input=payload,
        text=True, stdout=subprocess.PIPE, check=True, timeout=90,
    ).stdout


def prometheus(profile, path):
    return json.loads(compose(profile, "exec", "-T", "prometheus", "wget", "-qO-",
                              "http://127.0.0.1:9090" + path))


def alarm_history(profile):
    return compose(profile, "exec", "-T", "alarm-receiver", "python", "/app/receiver.py", "history")


def backup_status(profile, verified, failed):
    if verified is not None and not 0 < verified <= int(time.time()):
        raise SystemExit("Use the UTC epoch of a successfully verified backup.")
    # Only the local Docker operator can publish this signal. A failed verification
    # keeps the most recent successful timestamp so overdue alerts remain meaningful.
    code = """
import json, os, sys, tempfile
from pathlib import Path

target = Path('/var/lib/iot/alarms/backup-status.json')
update = json.load(sys.stdin)
if update['verified_at'] is None and target.exists():
    update['verified_at'] = json.loads(target.read_text())['verified_at']
with tempfile.NamedTemporaryFile(dir=target.parent, delete=False, mode='w') as output:
    json.dump(update, output)
os.replace(output.name, target)
"""
    compose(profile, "exec", "-T", "--user", "10001:10001", "alarm-receiver", "python", "-c", code,
            payload=json.dumps({"verified_at": verified, "checksum_failed": failed}))


def atomic_write(path, content):
    temporary = path.with_name("." + path.name + ".new")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def export(profile, run_id, now):
    day = now.date().isoformat()
    folder = ROOT / "run-artifacts" / run_id
    evidence = folder / "metrics" / day
    evidence.mkdir(parents=True, exist_ok=True)
    end = int(now.timestamp())
    since = datetime.fromtimestamp(end - 86400, timezone.utc)
    selection = (
        '{__name__=~"up|iot_.*|http_server_requests_seconds_count|'
        'process_cpu_usage|process_uptime_seconds|jvm_memory_used_bytes|hikaricp_connections_active"}'
    )
    query = urlencode({"query": selection, "start": end - 86400, "end": end, "step": "15m"})
    history = prometheus(profile, "/api/v1/query_range?" + query)["data"]["result"]
    content = io.StringIO()
    writer = csv.writer(content)
    writer.writerow(["run_id", "timestamp_utc", "metric", "labels", "value"])
    for series in history:
        labels = series["metric"].copy()
        metric = labels.pop("__name__", "")
        for observed, value in series["values"]:
            stamp = datetime.fromtimestamp(observed, timezone.utc).isoformat()
            writer.writerow([run_id, stamp, metric, json.dumps(labels, sort_keys=True), value])
    atomic_write(evidence / "metrics.csv", content.getvalue())
    atomic_write(evidence / "containers.ndjson",
                 compose(profile, "stats", "--no-stream", "--format", "json"))
    containers = compose(profile, "ps", "--all", "--quiet").splitlines()
    restarts = subprocess.run(
        ["docker", "inspect", "--format", "{{json .Name}},{{.RestartCount}}", *containers],
        cwd=ROOT, text=True, stdout=subprocess.PIPE, check=True, timeout=15,
    ).stdout
    atomic_write(evidence / "container-restarts.csv", "container,restarts\n" + restarts)
    disk = shutil.disk_usage(ROOT)
    atomic_write(evidence / "host-disk.csv",
                 f"run_id,timestamp_utc,total_bytes,used_bytes,free_bytes\n{run_id},{now.isoformat()},{disk.total},{disk.used},{disk.free}\n")
    atomic_write(evidence / "compose.log",
                 compose(profile, "logs", "--since", since.isoformat(), "--no-color"))
    atomic_write(folder / "alarms.ndjson", alarm_history(profile))
    return evidence


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=("local", "test"))
    actions = parser.add_subparsers(dest="action", required=True)
    for action in ("history", "rules", "alerts"):
        actions.add_parser(action)
    for action in ("export", "watch"):
        actions.add_parser(action).add_argument("run_id")
    backup = actions.add_parser("backup-status")
    backup.add_argument("--verified-epoch", type=int)
    backup.add_argument("--checksum-failed", action="store_true")
    args = parser.parse_args()
    profile = args.profile
    if args.action == "history":
        print(alarm_history(profile), end="")
    elif args.action == "rules":
        print(json.dumps(prometheus(profile, "/api/v1/rules"), indent=2))
    elif args.action == "alerts":
        print(compose(profile, "exec", "-T", "alertmanager", "wget", "-qO-",
                      "http://127.0.0.1:9093/api/v2/alerts/groups"), end="")
    elif args.action == "backup-status":
        backup_status(profile, args.verified_epoch, args.checksum_failed)
    else:
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}", args.run_id):
            parser.error("Use up to 64 characters from A-Z, a-z, 0-9, underscore or hyphen for the run ID.")
        while True:
            now = datetime.now(timezone.utc)
            marker = ROOT / "run-artifacts" / args.run_id / "metrics" / now.date().isoformat() / ".done"
            if args.action == "export" or not marker.exists():
                try:
                    evidence = export(profile, args.run_id, now)
                    atomic_write(evidence / ".done", now.isoformat() + "\n")
                    print(evidence, flush=True)
                except (OSError, subprocess.SubprocessError, ValueError, KeyError) as error:
                    if args.action == "export":
                        raise
                    print(f"Observation export will retry: {type(error).__name__}", flush=True)
            if args.action == "export":
                return
            time.sleep(60)


if __name__ == "__main__":
    main()
