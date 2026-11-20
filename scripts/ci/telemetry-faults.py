#!/usr/bin/env python3
"""Exercise telemetry durability in an isolated Compose project using built test images."""

import json
import os
import re
import sqlite3
import struct
import subprocess
import tempfile
import time
from collections import Counter
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from uuid import UUID, uuid4

from browser_api import Browser

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "build/ci/faults"
TENANT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
TENANT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
TENANTS = (TENANT_A, TENANT_B)
DEVICE = "00000000-0000-4000-8000-000000000002"
SENSORS = tuple(f"00000000-0000-4000-8000-{index:012d}" for index in range(1, 7))
SILENCE = "/var/lib/iot/fault-silence"
WITHHOLD_ACK = "/var/lib/iot/fault-command-ack"

GEN = "iot_simulator_generated_events_total"
OUTBOX = "iot_simulator_pending_events"
ACCEPTED = "iot_gateway_accepted_events_total"
DUPLICATES = "iot_gateway_duplicate_events_total"
REJECTED = "iot_gateway_rejected_events_total"
SPOOL = "iot_gateway_spool_events"
OLDEST = "iot_gateway_spool_oldest_seconds"


def utc():
    return datetime.now(timezone.utc).isoformat()


def require(condition, detail):
    if not condition:
        raise AssertionError(detail)


def eventually(label, predicate, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(1)
    raise AssertionError(f"Timed out waiting for {label}")


def varint(number):
    data = bytearray()
    while number >= 128:
        data.append((number & 127) | 128)
        number >>= 7
    data.append(number)
    return bytes(data)


def field(tag, value):
    data = value.encode("ascii")
    return bytes([tag]) + varint(len(data)) + data


def reading(sensor, event, observed, value):
    # The five v1 fields from contracts/proto/telemetry.proto.
    payload = b"\x08\x01" + field(0x12, sensor) + field(0x1A, event)
    payload += b"\x20" + varint(observed)
    return payload if value is None else payload + b"\x29" + struct.pack("<d", value)


def sampling_command(sensor, version, enabled, expires):
    # The six v1 fields from contracts/proto/control.proto.
    return (b"\x08\x01" + field(0x12, str(uuid4())) + field(0x1A, sensor)
            + b"\x20" + varint(version) + b"\x28" + varint(enabled)
            + b"\x30" + varint(expires))


def in_both_tenants(after, before, increment=1):
    return all(after[tenant] >= before[tenant] + increment for tenant in TENANTS)


class Suite:
    def __init__(self):
        self.project = f"iot-platform-fault-{uuid4().hex[:10]}"
        self.command = [
            "docker", "compose", "--env-file", ".env.test", "-f", "compose.yaml",
            "-f", "compose.fault.yaml", "--project-name", self.project, "--profile", "test",
        ]
        self.environment = dict(os.environ, IOT_STACK_IMAGE_TAG="test", IOT_HTTP_PORT="0")
        self.results = []
        self.fixtures = {}
        self.withheld = set()

    def run(self, *args, payload=None, check=True, timeout=35):
        result = subprocess.run(
            [*self.command, *args], cwd=ROOT, env=self.environment, input=payload,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,
        )
        if check and result.returncode:
            operation = " ".join(args[:3])
            raise RuntimeError(
                f"Compose {operation} failed ({result.returncode})\n"
                f"{result.stderr.decode(errors='replace').strip()}"
            )
        return result

    def up(self, *services, recreate=False, dependencies=False):
        args = ["up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "110"]
        if not dependencies:
            args.append("--no-deps")
        if recreate:
            args.append("--force-recreate")
        self.run(*args, *services, timeout=135)

    def exec(self, service, *args):
        return self.run("exec", "-T", service, *args).stdout.decode().strip()

    def sign_in(self):
        port = self.run("port", "frontend", "8080").stdout.decode().strip().rsplit(":", 1)[1]
        self.browsers = {operator: Browser(ROOT / ".env.test", port, operator)
                         for operator in ("a", "b")}

    def sql(self, query):
        return self.exec("timescaledb", "psql", "-X", "-qAt", "-F", "|", "-v",
                         "ON_ERROR_STOP=1", "-U", "iot", "-d", "iot", "-c", query)

    def committed(self):
        rows = self.sql("SELECT tenant_id, count(*) FROM telemetry.readings GROUP BY tenant_id")
        counts = {tenant: 0 for tenant in TENANTS}
        for row in rows.splitlines():
            tenant, count = row.split("|")
            require(tenant in counts, "Unexpected tenant in database")
            counts[tenant] = int(count)
        return counts

    def metrics(self, service, *names):
        exposition = self.exec(service, "wget", "-qO-", "http://127.0.0.1:8080/actuator/prometheus")
        found = {}
        for name in names:
            pattern = rf'^{re.escape(name)}\{{tenant="([^"]+)"\}}\s+(\S+)'
            values = {tenant: float(value)
                      for tenant, value in re.findall(pattern, exposition, re.MULTILINE)}
            require(set(values) == set(TENANTS), f"Missing tenant metric {name}")
            found[name] = values
        return found

    def generated(self):
        return self.metrics("simulator", GEN)[GEN]

    def drain(self):
        eventually("both durable queues to drain", lambda:
                   not any(self.metrics("simulator", OUTBOX)[OUTBOX].values())
                   and not any(self.metrics("gateway", SPOOL)[SPOOL].values()), timeout=65)

    def case(self, name, action):
        record = {"id": name, "started_at": utc()}
        self.results.append(record)
        try:
            record["before"] = self.snapshot()
            record["fault_started_at"] = utc()
            record["observed"] = action()
            record["recovery_observed_at"] = utc()
            record["after"] = self.snapshot()
            record["outcome"] = "passed"
            print(f"{name} passed", flush=True)
        except Exception as failure:
            record.update(outcome="failed", error=str(failure))
            raise
        finally:
            record["finished_at"] = utc()
            logs = self.run("logs", "--no-color", check=False, timeout=40)
            (EVIDENCE / f"{name}.log").write_bytes(logs.stdout + logs.stderr)

    def snapshot(self):
        return {"at": utc(), "database": self.committed(),
                "simulator": self.metrics("simulator", GEN, OUTBOX),
                "gateway": self.metrics("gateway", ACCEPTED, DUPLICATES, REJECTED, SPOOL, OLDEST)}

    def silence(self, enabled):
        self.exec("simulator", "touch" if enabled else "rm", SILENCE)
        if enabled:
            # Wait out the simulator's five-second metrics snapshot interval before checking drain.
            time.sleep(5.5)

    def receipt_loss(self):
        def matching_replays():
            log = self.run("logs", "--no-color", "gateway").stdout.decode()
            key = rf"iot/v1/([^/]+)/{DEVICE}/([^/]+)/telemetry ([0-9a-f-]+)"
            withheld = set(re.findall("withheld application receipt " + key, log))
            replayed = set(re.findall("observed unchanged replay " + key, log))
            matched = withheld & replayed
            return matched if {row[0] for row in matched} == set(TENANTS) else None

        self.withheld = eventually("withheld application receipts to replay unchanged", matching_replays)
        return {"withheld_and_replayed": sorted(self.withheld)}

    def heartbeat(self, expected, tenant=TENANT_A, subscriber=TENANT_A):
        topic = f"iot/v1/{tenant}/{DEVICE}/{SENSORS[0]}/heartbeat"
        account = "a" if subscriber == TENANT_A else "b"
        result = self.run(
            "exec", "-T", "mosquitto", "sh", "-ec",
            'case "$2" in a) password="$GATEWAY_A_PASSWORD";; b) password="$GATEWAY_B_PASSWORD";; esac; '
            'exec mosquitto_sub -V mqttv5 -h 127.0.0.1 -u "gateway-$2" -P "$password" '
            '-t "$1" -C 1 -W 7 -F "%x"',
            "probe", topic, account, check=False, timeout=12)
        if expected:
            require(result.returncode == 0 and result.stdout.strip(),
                    "Expected a heartbeat on the authorized tenant topic")
            return bytes.fromhex(result.stdout.decode().strip())
        else:
            output = (result.stdout + result.stderr).lower()
            blocked = subscriber != tenant and (b"not authorized" in output or b"denied" in output)
            require(result.returncode != 0 and (b"timed out" in output or blocked),
                    "Unexpected delivery on a stopped or unauthorized heartbeat topic")

    def simulator_silence(self):
        def status(connectivity, samples):
            return eventually(f"browser to report {connectivity}/{samples}", lambda:
                              self.sensor_status(connectivity, samples))

        before_silence = status("online", "fresh")
        self.silence(True)
        self.drain()
        before = self.committed()
        self.heartbeat(expected=True)
        stale = status("online", "stale")
        require(self.committed() == before, "Readings advanced while heartbeats continued during silence")
        self.run("stop", "simulator")
        self.heartbeat(expected=False)
        offline = status("offline", "stale")
        self.up("simulator")
        self.silence(False)
        eventually("both tenants to resume commits", lambda: in_both_tenants(self.committed(), before))
        recovered = status("online", "fresh")
        require(stale["lastObservedAt"] == offline["lastObservedAt"]
                and stale["lastObservedAt"] != recovered["lastObservedAt"],
                "Browser observation timestamps did not preserve the sampling gap")
        return {"baseline": before_silence, "silence": stale, "stopped": offline,
                "recovery": recovered}

    def sensor_status(self, connectivity, samples, operator="a", after_heartbeat=None):
        code, registry = self.browsers[operator].call("/sensors")
        require(code == 200, "Tenant registry is unreadable")
        sensor = next(item for item in registry["items"] if item["sensorId"] == SENSORS[0])
        if (sensor["connectivity"] == connectivity and sensor["sampleStatus"] == samples
                and sensor["lastHeartbeatAt"] != after_heartbeat):
            return sensor

    def issue(self, enabled, key):
        code, command = self.browsers["a"].post(f"/sensors/{SENSORS[0]}/commands",
                                                {"enabled": enabled}, {"Idempotency-Key": key})
        require(code == 202, f"Browser command returned {code}")
        return command

    def command_status(self, command):
        code, result = self.browsers["a"].call(f"/commands/{command['commandId']}")
        require(code == 200, "Browser command is unreadable")
        return result["status"]

    def paused_heartbeat(self, version):
        payload = self.heartbeat(expected=True)
        # Skip the encoded observation timestamp. A paused heartbeat omits field 4 (enabled).
        prefix = b"\x08\x01" + field(0x12, SENSORS[0]) + b"\x18"
        require(payload.startswith(prefix), "Unexpected heartbeat payload")
        remaining = payload[len(prefix):]
        observed_end = next(index for index, value in enumerate(remaining) if value < 128)
        return remaining[observed_end + 1:] == b"\x28" + varint(version)

    def command_ack_loss(self):
        self.exec("simulator", "touch", WITHHOLD_ACK)
        paused = self.issue(False, "fault-pause")
        eventually("device to report the applied pause without an ACK", lambda:
                   self.paused_heartbeat(paused["version"]))
        require(self.command_status(paused) == "pending", "Withheld ACK reached the backend")
        require(self.browsers["b"].call(f"/commands/{paused['commandId']}")[0] == 404,
                "An operator could read the other tenant's command")
        self.run("stop", "simulator")
        with tempfile.TemporaryDirectory(prefix="iot-command-state-") as tmp:
            stopped = self.snapshot_database("simulator", "simulator.sqlite", Path(tmp))
            with closing(sqlite3.connect(stopped)) as audit:
                applied = audit.execute(
                    "SELECT command,version,enabled FROM sampling_state WHERE tenant=? AND sensor=?",
                    (TENANT_A, SENSORS[0]),
                ).fetchone()
        require(applied == (paused["commandId"], paused["version"], 0),
                "Applied command did not persist in the simulator")
        self.up("simulator", recreate=True)
        eventually("device to report the persisted pause after replacement", lambda:
                   self.paused_heartbeat(paused["version"]))
        require(self.command_status(paused) == "pending", "Restart bypassed the ACK fault")
        self.exec("simulator", "rm", WITHHOLD_ACK)
        eventually("stored pause ACK to reconcile", lambda: self.command_status(paused) == "applied")
        confirmed_pause = eventually("browser to show the acknowledged pause", lambda:
                                     self.sensor_status("online", "paused"))
        require(self.issue(False, "fault-pause")["commandId"] == paused["commandId"],
                "An idempotent retry created a second pause command")

        topic = f"iot/v1/{TENANT_A}/{DEVICE}/{SENSORS[0]}/command"
        expired = sampling_command(SENSORS[0], paused["version"] + 1, True,
                                   int(time.time() * 1000) - 1000)
        self.publish_packet(topic, expired, "gateway-a")

        def paused_without_alert():
            sensor = self.sensor_status("online", "paused", after_heartbeat=confirmed_pause["lastHeartbeatAt"])
            if sensor is None:
                return None
            last = datetime.fromisoformat(sensor["lastObservedAt"].replace("Z", "+00:00"))
            if (datetime.now(timezone.utc) - last).total_seconds() < 10:
                return None
            metrics = self.exec("backend", "wget", "-qO-", "http://127.0.0.1:8080/actuator/prometheus")
            values = dict(re.findall(r"^(iot_sensor_(?:stale|offline)) (\S+)$", metrics, re.MULTILINE))
            return sensor if len(values) == 2 and all(float(value) == 0 for value in values.values()) else None

        quiet = eventually("paused sensor to stay outside the enabled-sensor alarm", paused_without_alert)

        resumed = self.issue(True, "fault-resume")
        eventually("valid enable to be acknowledged", lambda: self.command_status(resumed) == "applied")
        live = eventually("sensor to report fresh samples", lambda: self.sensor_status("online", "fresh"))
        stale = sampling_command(SENSORS[0], paused["version"], False,
                                 int(time.time() * 1000) + 30_000)
        self.publish_packet(topic, stale, "gateway-a")
        self.publish_packet(topic.replace(TENANT_A, TENANT_B), stale, "gateway-a", allowed=False)
        eventually("fresh samples after older pause", lambda:
                   self.sensor_status("online", "fresh", after_heartbeat=live["lastHeartbeatAt"]))
        eventually("tenant B to stay online with fresh samples", lambda:
                   self.sensor_status("online", "fresh", operator="b"))
        return {"paused_version": paused["version"], "resumed_version": resumed["version"],
                "ack_withheld_across_restart": True, "expired_and_older_commands_ignored": True,
                "paused_without_sensor_alarm": quiet, "cross_tenant_command_denied": True}

    def broker_restart(self):
        before = self.generated()
        commits = self.committed()
        old_broker = self.exec_id("mosquitto")
        self.run("stop", "mosquitto")
        eventually("producer growth during broker loss", lambda:
                   in_both_tenants(self.generated(), before) and
                   all(self.metrics("simulator", OUTBOX)[OUTBOX].values()))
        pending = self.metrics("simulator", OUTBOX)[OUTBOX]
        self.up("mosquitto", recreate=True)
        require(self.exec_id("mosquitto") != old_broker, "Broker reused the old container")
        eventually("commits after fresh broker session", lambda: in_both_tenants(self.committed(), commits))
        return {"producer_pending_during_fault": pending, "broker_data": "empty tmpfs"}

    def exec_id(self, service):
        return self.run("ps", "-q", service).stdout.decode().strip()

    def gateway_restart(self):
        before = self.generated()
        commits = self.committed()
        self.run("stop", "gateway")
        eventually("production to buffer without the gateway", lambda:
                   in_both_tenants(self.generated(), before) and
                   all(self.metrics("simulator", OUTBOX)[OUTBOX].values()))
        pending = self.metrics("simulator", OUTBOX)[OUTBOX]
        self.up("gateway", recreate=True)
        eventually("commits after gateway restart", lambda: in_both_tenants(self.committed(), commits))
        return {"producer_pending_during_fault": pending}

    def storage_outage(self):
        commits = self.committed()
        queued = self.metrics("gateway", SPOOL)[SPOOL]
        self.run("stop", "timescaledb")
        eventually("gateway spool to grow for both tenants", lambda:
                   in_both_tenants(self.metrics("gateway", SPOOL)[SPOOL], queued, increment=5))
        durable = self.metrics("gateway", SPOOL)[SPOOL]
        self.up("gateway", recreate=True)
        require(not self.exec_id("timescaledb"), "Gateway restart brought the stopped database online")
        eventually("cached registry and pending spool after gateway restart", lambda:
                   in_both_tenants(self.metrics("gateway", SPOOL)[SPOOL], durable, increment=0))
        self.run("stop", "backend")
        self.up("timescaledb")
        self.up("backend")
        eventually("commits after database and backend recovery",
                   lambda: in_both_tenants(self.committed(), commits), timeout=65)
        self.sign_in()
        return {"durable_spool_during_fault": durable}

    def publish(self, tenant, sensor, body, allowed=True):
        topic = f"iot/v1/{tenant}/{DEVICE}/{sensor}/telemetry"
        self.publish_packet(topic, body, "device-a", allowed)

    def publish_packet(self, topic, body, principal, allowed=True):
        result = self.run(
            "exec", "-T", "mosquitto", "sh", "-ec",
            'case "$2" in device-a) password="$DEVICE_A_PASSWORD";; '
            'gateway-a) password="$GATEWAY_A_PASSWORD";; *) exit 2;; esac; '
            'exec mosquitto_pub -V mqttv5 -h 127.0.0.1 -u "$2" -P "$password" '
            '-q 1 -d -t "$1" -s', "fixture", topic, principal, payload=body, check=False)
        output = (result.stdout + result.stderr).lower()
        denied = b"not authorized" in output
        if allowed:
            require(result.returncode == 0 and not denied, "Authorized fixture publication was refused")
        else:
            require(denied or result.returncode == 135, "Broker did not deny cross-tenant publication")

    def trend(self, operator, start, end, bucket):
        query = urlencode({"sensorId": SENSORS[0], "from": start, "to": end, "bucket": bucket})
        code, result = self.browsers[operator].call(f"/trends?{query}")
        require(code == 200 and len(result["series"]) == 1,
                f"Fault trend is unreadable: HTTP {code}, {result.get('code')}")
        return result["series"][0]

    def analytics_repair(self):
        hour = 3_600_000
        start_ms = int(time.time() * 1000) // hour * hour - 60 * hour
        end_ms = start_ms + hour
        start = datetime.fromtimestamp(start_ms / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        end = datetime.fromtimestamp(end_ms / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        jobs = dict(row.split("|") for row in self.sql("""
            SELECT proc_name, job_id FROM timescaledb_information.jobs
            WHERE proc_schema='telemetry' AND proc_name IN ('refresh_recent_analytics','repair_analytics')
            """).splitlines())
        refresh, repair = (int(jobs[name]) for name in ("refresh_recent_analytics", "repair_analytics"))
        self.sql(f"SELECT public.alter_job(job_id, scheduled => false) "
                 f"FROM timescaledb_information.jobs WHERE job_id IN ({refresh},{repair})")
        self.silence(True)
        self.drain()

        def backfill(fixtures):
            before = self.committed()
            for offset, value in fixtures:
                event = str(uuid4())
                observed = start_ms + offset
                self.publish(TENANT_A, SENSORS[0], reading(SENSORS[0], event, observed, value))
                self.fixtures[(TENANT_A, SENSORS[0], event)] = observed
            eventually("historical MQTT fixtures to commit", lambda:
                       self.committed() == {**before, TENANT_A: before[TENANT_A] + len(fixtures)})

        backfill(((1000, 10), (901_000, 40), (902_000, 60)))
        self.sql(f"CALL public.run_job({refresh})")
        self.sql(f"CALL public.run_job({repair})")
        fresh = self.trend("a", start, end, "15m")
        require(fresh["freshness"]["state"] == "fresh" and fresh["buckets"][1]["count"] == 2,
                "Baseline historical materialization is missing")
        self.sql("UPDATE telemetry.analytics_coverage SET recent_refreshed_at=now()-interval '5 minutes'")
        stale = self.trend("a", start, end, "15m")
        require(stale["freshness"]["state"] == "stale", "The delayed refresh was not visible to the browser")
        backfill(((900_000, 30),))
        pending = self.trend("a", start, end, "15m")
        require(pending["freshness"]["state"] == "repair_pending", "Late reading did not mark its bucket")
        self.sql(f"CALL public.run_job({repair})")
        self.sql(f"CALL public.run_job({refresh})")
        repaired = self.trend("a", start, end, "15m")
        require(repaired["freshness"]["state"] == "fresh", "Repair did not restore freshness")
        buckets = repaired["buckets"]
        require([bucket["count"] for bucket in buckets] == [1, 3, 0, 0]
                and all(buckets[2][key] is None for key in ("min", "max", "mean")),
                "The boundary reading or missing buckets are incorrect")
        raw = tuple(float(value) for value in self.sql(f"""
            SELECT count(*), min(value), max(value), avg(value) FROM telemetry.readings
            WHERE tenant_id='{TENANT_A}' AND sensor_id='{SENSORS[0]}'
              AND observed_at>=to_timestamp({start_ms // 1000}) AND observed_at<to_timestamp({end_ms // 1000})
            """).split("|"))
        rolled_up = self.trend("a", start, end, "1h")["buckets"][0]
        require(tuple(rolled_up[key] for key in ("count", "min", "max", "mean")) == raw == (4, 10, 60, 35),
                "Repaired hourly rollup disagrees with the raw counts or weighted statistics")
        unrelated = self.trend("b", start, end, "15m")
        require(all(item["count"] == 0 for item in unrelated["buckets"]),
                "Another tenant could read the recovered historical fixtures")
        self.sql(f"SELECT public.alter_job(job_id, scheduled => true) "
                 f"FROM timescaledb_information.jobs WHERE job_id IN ({refresh},{repair})")
        self.silence(False)
        return {"stale": stale["freshness"], "pending": pending["freshness"],
                "repaired": repaired, "hourly": rolled_up, "other_tenant": unrelated}

    def acl_and_schema(self):
        self.heartbeat(expected=True, tenant=TENANT_B, subscriber=TENANT_B)
        self.heartbeat(expected=False, tenant=TENANT_B, subscriber=TENANT_A)
        self.silence(True)
        self.drain()
        before = self.metrics("gateway", ACCEPTED, DUPLICATES, REJECTED)
        commits = self.committed()
        self.run("stop", "backend")
        observed = int(time.time() * 1000)
        valid_values = (20, 45, 1013, 15, 50, 21)
        invalid_values = (200, 101, 3000, -1, -1, 101)
        for index, (sensor, valid, invalid) in enumerate(zip(SENSORS, valid_values, invalid_values)):
            event = str(UUID(int=index + 1))
            payload = reading(sensor, event, observed, valid)
            self.publish(TENANT_A, sensor, payload)
            self.fixtures[(TENANT_A, sensor, event)] = observed
            self.publish(TENANT_A, sensor, reading(sensor, str(UUID(int=index + 11)), observed, invalid))
            if index == 0:
                self.publish(TENANT_A, sensor, payload)
                self.publish(TENANT_A, sensor, reading(sensor, event, observed, valid + 1))
        self.publish(TENANT_A, SENSORS[0], reading(SENSORS[0], str(UUID(int=21)), observed, None))
        self.publish(TENANT_A, SENSORS[0], b"\x08")
        self.publish(TENANT_B, SENSORS[0], reading(SENSORS[0], str(UUID(int=22)), observed, 20),
                     allowed=False)

        def accounted():
            actual = self.metrics("gateway", ACCEPTED, DUPLICATES, REJECTED)
            expected = {ACCEPTED: 6, DUPLICATES: 1, REJECTED: 9}
            return actual if all(actual[key][TENANT_A] >= before[key][TENANT_A] + count
                                 for key, count in expected.items()) else None

        actual = eventually("valid, duplicate and rejected MQTT fixture outcomes", accounted)
        for name, count in ((ACCEPTED, 6), (DUPLICATES, 1), (REJECTED, 9)):
            require(actual[name][TENANT_A] - before[name][TENANT_A] == count, f"Unexpected {name} delta")
            require(actual[name][TENANT_B] == before[name][TENANT_B], f"Tenant B was affected by {name}")
        self.up("backend")
        eventually("six valid fixture commits", lambda: self.committed()[TENANT_A] == commits[TENANT_A] + 6,
                   timeout=65)
        require(self.committed()[TENANT_B] == commits[TENANT_B], "Cross-tenant fixture reached storage")
        return {"valid": 6, "duplicate": 1, "rejected": 9, "cross_tenant_publish_denied": True,
                "cross_tenant_delivery_denied": True}

    def snapshot_database(self, service, filename, scratch):
        target = scratch / filename
        source = f"{service}:/var/lib/iot/{filename}"
        self.run("cp", source, str(target))
        require(target.is_file() and target.stat().st_size > 0, f"Missing {service} SQLite snapshot")
        wal = self.run("cp", f"{source}-wal", str(scratch / f"{filename}-wal"), check=False)
        if wal.returncode:
            detail = wal.stderr.decode().lower()
            require("could not find" in detail or "no such file" in detail,
                    f"Could not copy {service}'s SQLite WAL")
        return target

    def reconcile(self):
        self.silence(True)
        self.drain()
        self.run("stop", "-t", "30", "simulator", "gateway", timeout=45)
        with tempfile.TemporaryDirectory(prefix="iot-fault-audit-") as scratch:
            db = self.snapshot_database("simulator", "simulator.sqlite", Path(scratch))
            # SQLite can rebuild its shared-memory index from the copied WAL, if one remains.
            with closing(sqlite3.connect(db)) as audit:
                generated = audit.execute(
                    "SELECT tenant, sensor, event, observed_ms, outcome FROM fault_events").fetchall()
                counters = dict(audit.execute("SELECT tenant, generated FROM stats"))
                require(audit.execute("SELECT count(*) FROM pending").fetchone() == (0,),
                        "Simulator outbox did not drain")
            gateway = self.snapshot_database("gateway", "gateway.sqlite", Path(scratch))
            with closing(sqlite3.connect(gateway)) as spool:
                require(spool.execute("SELECT count(*) FROM spool").fetchone() == (0,),
                        "Gateway spool did not drain")
                rejections = {(tenant, stage, reason): count for tenant, stage, reason, count
                              in spool.execute("SELECT tenant, stage, reason, count FROM rejection_totals")}
            require(rejections == {
                (TENANT_A, "mqtt", "event-conflict"): 1,
                (TENANT_A, "mqtt", "invalid-reading"): 7,
                (TENANT_A, "mqtt", "malformed-protobuf"): 1,
            }, f"Unexpected reason-coded gateway outcomes: {rejections}")

        expected = dict(self.fixtures)
        generated_count = Counter()
        for tenant, sensor, event, at, outcome in generated:
            require(outcome == "accepted", f"Nonterminal simulator event {tenant}/{sensor}/{event}: {outcome}")
            require((tenant, sensor, event) not in expected, "Repeated fixture or generator identity")
            expected[tenant, sensor, event] = at
            generated_count[tenant] += 1
        require(dict(generated_count) == counters, "Generator counters and identity journal differ")
        for key in self.withheld:
            require(key in expected, f"Lost-receipt witness missing from simulator trace: {key}")
        rows = self.sql("""
            SELECT tenant_id, sensor_id, event_id, (extract(epoch FROM observed_at)*1000)::bigint
            FROM telemetry.readings ORDER BY tenant_id, sensor_id, event_id
            """).splitlines()
        actual = {tuple(row.split("|")[:3]): int(row.rsplit("|", 1)[1]) for row in rows}
        require(len(rows) == len(actual), "Duplicate identities persisted in TimescaleDB")
        require(actual == expected,
                f"Terminal mismatch: {len(expected.keys() - actual.keys())} missing identities, "
                f"{len(actual.keys() - expected.keys())} unexpected identities, "
                f"{sum(actual[key] != expected[key] for key in actual.keys() & expected.keys())} changed timestamps")
        ledger = {tuple(row.split("|")) for row in self.sql(
            "SELECT tenant_id,sensor_id,event_id FROM telemetry.event_receipts").splitlines()}
        require(ledger == set(expected), "Receipt ledger and committed identities differ")
        evidence = [{"tenant": t, "sensor": s, "event": e, "observed_ms": at, "terminal": "committed"}
                    for (t, s, e), at in sorted(expected.items())]
        (EVIDENCE / "terminal-events.json").write_text(json.dumps(evidence, indent=2) + "\n")
        summary = {"generated_per_tenant": dict(generated_count), "committed_per_tenant": self.committed(),
                   "explicit_fixture_events": len(self.fixtures),
                   "fixture_rejections_by_reason": {reason: count
                                                    for (_, _, reason), count in rejections.items()},
                   "duplicates": 0, "unexplained_losses": 0}
        print(f"Exact identity reconciliation passed: {summary}", flush=True)
        return summary

    def execute(self):
        self.up("simulator", "gateway", "frontend", dependencies=True)
        self.sign_in()
        eventually("initial commits for both tenants",
                   lambda: all(self.committed().values()))
        self.case("FT-01B-receipt-loss", self.receipt_loss)
        self.case("FT-01A-simulator-silence", self.simulator_silence)
        self.case("FT-01B-broker-restart", self.broker_restart)
        self.case("FT-01B-gateway-restart", self.gateway_restart)
        self.case("FT-01D-database-backend-outage", self.storage_outage)
        self.case("FT-01E-stale-trend-repair", self.analytics_repair)
        self.case("FT-01F-command-ack-loss", self.command_ack_loss)
        self.case("FT-01C-schema-and-tenant-isolation", self.acl_and_schema)
        return self.reconcile()


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    suite = Suite()
    report = {"project": suite.project, "fixture": {"seed": 20260301, "streams_per_type": 1},
              "started_at": utc(), "cases": suite.results}
    try:
        report["reconciliation"] = suite.execute()
        report["outcome"] = "passed"
    except Exception as failure:
        report.update(outcome="failed", error=str(failure))
        raise
    finally:
        report["finished_at"] = utc()
        try:
            (EVIDENCE / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")
            logs = suite.run("logs", "--no-color", check=False, timeout=40)
            (EVIDENCE / "compose.log").write_bytes(logs.stdout + logs.stderr)
        finally:
            suite.run("down", "--volumes", "--remove-orphans", timeout=60)


if __name__ == "__main__":
    main()
