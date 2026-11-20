"""Local Alertmanager receiver. Only the Docker operator can read the ledger."""
import argparse
import hmac
import json
import os
import re
import shutil
import sqlite3
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DATA = Path("/var/lib/iot/alarms")
MAX_BODY = 262_144
WATCHDOG_GRACE = 90
BROKER_METRICS = {
    "$SYS/broker/clients/connected": "iot_broker_connected_clients",
    "$SYS/broker/publish/messages/dropped": "iot_broker_dropped_messages_total",
    "$SYS/broker/messages/received": "iot_broker_received_messages_total",
}
SCHEMA = """
CREATE TABLE IF NOT EXISTS incidents (
    fingerprint TEXT NOT NULL, starts_at TEXT NOT NULL, name TEXT NOT NULL,
    severity TEXT NOT NULL, stage TEXT NOT NULL, tenant TEXT, runbook TEXT NOT NULL,
    status TEXT NOT NULL, received_at TEXT NOT NULL, resolved_at TEXT,
    PRIMARY KEY (fingerprint, starts_at)
);
CREATE TABLE IF NOT EXISTS watchdog (
    id INTEGER PRIMARY KEY CHECK (id=1), started INTEGER NOT NULL, last_delivery INTEGER
);
CREATE TABLE IF NOT EXISTS monitoring_gaps (
    starts INTEGER PRIMARY KEY, ends INTEGER
);
"""


def stamp(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).isoformat(timespec="seconds")


@contextmanager
def ledger():
    connection = sqlite3.connect(DATA / "alarms.sqlite", timeout=3)
    connection.row_factory = sqlite3.Row
    try:
        connection.execute("PRAGMA synchronous=FULL")
        with connection:
            yield connection
    finally:
        connection.close()


def record_gap(connection, now):
    baseline = connection.execute(
        "SELECT coalesce(last_delivery,started) FROM watchdog WHERE id=1"
    ).fetchone()[0]
    if now - baseline >= WATCHDOG_GRACE:
        connection.execute(
            "INSERT OR IGNORE INTO monitoring_gaps (starts) VALUES (?)", (baseline,)
        )


def validated_alert(alert):
    if not isinstance(alert, dict):
        raise ValueError("invalid alert")
    labels, annotations = alert["labels"], alert["annotations"]
    if not isinstance(labels, dict) or not isinstance(annotations, dict):
        raise ValueError("invalid labels")
    fingerprint = alert["fingerprint"]
    if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{16}", fingerprint):
        raise ValueError("invalid fingerprint")
    status = alert["status"]
    if status not in ("firing", "resolved"):
        raise ValueError("invalid status")

    def timestamp(value):
        moment = datetime.fromisoformat(value)
        if moment.tzinfo is None:
            raise ValueError("missing time zone")
        return moment.astimezone(timezone.utc).isoformat(timespec="microseconds")

    starts = timestamp(alert["startsAt"])
    ends = timestamp(alert["endsAt"]) if status == "resolved" else None
    if ends and ends < starts:
        raise ValueError("invalid resolution")
    fields = [labels["alertname"], labels.get("severity", "info"),
              labels.get("stage", "monitoring"), labels.get("tenant", ""),
              annotations.get("runbook", "")]
    if any(not isinstance(value, str) or len(value) > 256 for value in fields):
        raise ValueError("invalid metadata")
    return (fingerprint, starts, *fields, status, ends)


def accept(alerts, now):
    with ledger() as connection:
        if any(row[2] == "Watchdog" and row[-2] == "firing" for row in alerts):
            record_gap(connection, now)
            connection.execute("UPDATE monitoring_gaps SET ends=? WHERE ends IS NULL", (now,))
            connection.execute("UPDATE watchdog SET last_delivery=? WHERE id=1", (now,))
        connection.executemany(
            """
            INSERT INTO incidents
              (fingerprint,starts_at,name,severity,stage,tenant,runbook,status,resolved_at,received_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(fingerprint,starts_at) DO UPDATE SET
              status='resolved', resolved_at=excluded.resolved_at
            WHERE incidents.status='firing' AND excluded.status='resolved'
            """,
            [(*row, stamp(now)) for row in alerts if row[2] != "Watchdog"],
        )



def external_metrics(now):
    lines = [f"iot_alarm_storage_available_bytes {shutil.disk_usage(DATA).free}"]
    broker = {}
    try:
        sample = Path("/run/iot-broker/metrics").read_text().splitlines()
        if 0 <= now - int(sample[0]) <= 45:
            for item in sample[1:]:
                topic, value = item.split()
                if topic in BROKER_METRICS:
                    broker[BROKER_METRICS[topic]] = int(value)
    except (OSError, ValueError, IndexError):
        broker.clear()
    lines.append(f"iot_broker_metrics_available {int(len(broker) == len(BROKER_METRICS))}")
    lines.extend(f"{name} {value}" for name, value in broker.items())
    configured = os.environ.get("IOT_BACKUP_CONFIGURED") == "true"
    target = Path("/run/iot-backup")
    if configured:
        try:
            online = (target / ".iot-target-id").read_text().strip() == os.environ["IOT_BACKUP_TARGET_ID"]
            if online:
                lines.append(f"iot_backup_available_bytes {shutil.disk_usage(target).free}")
        except OSError:
            online = False
        lines.append(f"iot_backup_target_available {int(online)}")
    try:
        backup = json.loads((DATA / "backup-status.json").read_text())
        if not isinstance(backup, dict) or not isinstance(backup.get("checksum_failed"), bool):
            raise ValueError("invalid backup status")
        verified = backup.get("verified_at")
        if verified is not None and (type(verified) is not int or not 0 < verified <= now):
            raise ValueError("invalid verified timestamp")
        lines.append("iot_backup_monitoring_enabled 1")
        lines.append(f"iot_backup_checksum_failed {int(backup['checksum_failed'])}")
        if verified is not None:
            lines.append(f"iot_backup_verified_timestamp_seconds {verified}")
    except FileNotFoundError:
        lines.append(f"iot_backup_monitoring_enabled {int(configured)}")
    except (OSError, ValueError):
        lines.append("iot_backup_monitoring_enabled 1")
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    server_version = "iot-alarm-receiver"
    timeout = 5

    def log_message(self, _format, *_values):
        # Do not emit headers, credential values or arbitrary request paths.
        return

    def reply(self, status, body=b""):
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path not in ("/healthz", "/metrics"):
            self.reply(404)
            return
        try:
            with ledger() as connection:
                if self.path == "/healthz":
                    connection.execute("BEGIN IMMEDIATE")
                else:
                    now = int(time.time())
                    record_gap(connection, now)
                    last = connection.execute("SELECT last_delivery FROM watchdog WHERE id=1")
                    gaps = connection.execute("SELECT count(*) FROM monitoring_gaps WHERE ends IS NULL")
                    body = (
                        f"iot_alarm_watchdog_last_delivery_seconds {last.fetchone()[0] or 0}\n"
                        f"iot_alarm_monitoring_gap {gaps.fetchone()[0]}\n"
                    )
            if self.path == "/healthz":
                self.reply(200, b"ok\n")
            else:
                self.reply(200, (body + external_metrics(now)).encode())
        except sqlite3.Error:
            self.reply(503)

    def do_POST(self):
        if self.path != "/webhook":
            self.reply(404)
            return
        supplied = self.headers.get("Authorization", "").encode()
        if not hmac.compare_digest(supplied, self.server.authorization):
            self.reply(401)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length > MAX_BODY:
                self.reply(413)
                return
            if length <= 0:
                raise ValueError("missing content length")
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict) or body.get("truncatedAlerts", 0):
                raise ValueError("invalid notification")
            alerts = body.get("alerts")
            if not isinstance(alerts, list) or not alerts:
                raise ValueError("missing alerts")
            rows = [validated_alert(alert) for alert in alerts]
        except (KeyError, ValueError, TypeError, OverflowError, TimeoutError):
            self.reply(400)
            return
        try:
            accept(rows, int(time.time()))
        except sqlite3.Error:
            print(json.dumps({"level": "error", "reason": "alarm_storage_unavailable"}), flush=True)
            self.reply(503)
            return
        self.reply(204)


def serve():
    token = os.environ["IOT_ALARM_WEBHOOK_TOKEN"]
    if not re.fullmatch(r"[0-9a-f]{48}", token):
        raise SystemExit("Expected the generated alarm webhook credential")
    # Existing installations may still have the empty, root-owned scaffold volume.
    if os.getuid() == 0:
        os.chown(DATA, 10001, 10001)
        os.setgroups([])
        os.setgid(10001)
        os.setuid(10001)
    os.umask(0o077)
    with ledger() as connection:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.executescript(SCHEMA)
        now = int(time.time())
        connection.execute("INSERT OR IGNORE INTO watchdog (id,started) VALUES (1,?)", (now,))
        record_gap(connection, now)
    with ThreadingHTTPServer(("0.0.0.0", 8080), Handler) as server:
        server.authorization = ("Bearer " + token).encode()
        server.serve_forever()


def history():
    with ledger() as connection:
        for incident in connection.execute("SELECT * FROM incidents ORDER BY starts_at,fingerprint"):
            print(json.dumps({"kind": "incident", **dict(incident)}))
        for start, end in connection.execute("SELECT * FROM monitoring_gaps ORDER BY starts"):
            print(json.dumps({"kind": "monitoring_gap", "starts_at": stamp(start),
                              "ends_at": stamp(end) if end else None}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("serve", "history"))
    if parser.parse_args().action == "serve":
        serve()
    else:
        history()
