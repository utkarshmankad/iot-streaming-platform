#!/usr/bin/env python3
"""Restore a cloud database while its simulated field site stays running."""

import json
import sys
import tempfile
import time
from pathlib import Path
from runpy import run_path
from uuid import uuid4

from browser_api import Browser

profile = run_path(str(Path(__file__).with_name("private-link.py")))
ROOT, PROJECT, ENV, COMPOSE = (profile[key] for key in ("ROOT", "PROJECT", "ENV", "COMPOSE"))
run, compose, database, container, commits, require, eventually, TENANTS = (
    profile[key] for key in ("run", "compose", "database", "container", "commits", "require", "eventually", "TENANTS")
)
sys.path.insert(0, str(ROOT / "scripts"))
import recovery

fixture = profile["helpers"]
Suite, TENANT_A, DEVICE, SENSORS, field, varint, sampling_command = (
    fixture[key] for key in ("Suite", "TENANT_A", "DEVICE", "SENSORS", "field", "varint", "sampling_command")
)
SENSOR = SENSORS[0]
TOPIC = f"iot/v1/{TENANT_A}/{DEVICE}/{SENSOR}"
RESTORE_REPORT = ROOT / "build/ci/private-link-recovery.json"
RESTORE_STATE = ROOT / "run-artifacts/private-link-recovery"


def browser():
    port = compose("port", "frontend", "8080").stdout.strip().rsplit(":", 1)[1]
    return Browser(".env.private-link", port)


def read_field():
    # The normal gateway connects to the running simulator through MQTT.
    mqtt_fixture = Suite()
    mqtt_fixture.command = COMPOSE
    mqtt_fixture.environment = ENV
    return mqtt_fixture


def sensor_state():
    return database(
        f"SELECT confirmed_enabled,applied_version FROM registry.sensor_state "
        f"WHERE tenant_id='{TENANT_A}' AND sensor_id='{SENSOR}'"
    )


def set_hold(hold, override):
    ENV["IOT_CONTROL_RESTORE_HOLD"] = str(hold).lower()
    run(*COMPOSE, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--wait",
        "--force-recreate", "backend")


def postgres_volume(override):
    run(*COMPOSE, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--wait", "timescaledb")


def issue(client, enabled, key):
    return client.post(f"/sensors/{SENSOR}/commands", {"enabled": enabled}, {"Idempotency-Key": key})


def run_drill():
    # These containers, database volumes and keys belong only to this drill.
    label = f"label=com.docker.compose.project={PROJECT}"
    require(not run("docker", "ps", "-aq", "--filter", label).stdout.strip(),
            "Recovery drill needs a fresh private-link project")
    require(not run("docker", "volume", "ls", "-q", "--filter", label).stdout.strip(),
            "Recovery drill found existing private-link volumes")
    require(not RESTORE_STATE.exists(), "Recovery drill needs unused field storage")
    ENV.update(IOT_PRIVATE_LINK_STATE=str(RESTORE_STATE), IOT_EDGE_STORAGE=str(RESTORE_STATE / "storage"))
    restored_project = f"iot-private-link-restore-{uuid4().hex[:8]}"
    restored_volume = restored_project + "_database"
    old_command = str(uuid4())
    old_apply = int(time.time() * 1000) - 10_000

    try:
        run("./scripts/stack.sh", "private-link", "up-built", capture=False)
        eventually("both tenants to connect", lambda: set(commits()) == set(TENANTS))
        survivor = container("simulator")
        gateway = container("gateway")
        database(f"""
          INSERT INTO registry.commands (tenant_id,command_id,sensor_id,device_id,gateway_id,operator_id,
              idempotency_key,version,requested_enabled,created_at,expires_at)
          SELECT s.tenant_id,'{old_command}',s.sensor_id,d.device_id,d.gateway_id,m.operator_id,
                 'surviving-field-late-ack',1,true,to_timestamp({old_apply - 1000} / 1000.0),
                 to_timestamp({old_apply + 1000} / 1000.0)
          FROM registry.sensors s JOIN registry.devices d USING(tenant_id,device_id)
          JOIN registry.operator_memberships m USING(tenant_id)
          WHERE s.tenant_id='{TENANT_A}' AND s.sensor_id='{SENSOR}';
          UPDATE registry.sensor_state SET command_version=1
          WHERE tenant_id='{TENANT_A}' AND sensor_id='{SENSOR}';
        """)
        with tempfile.TemporaryDirectory(prefix="iot-cloud-restore-") as scratch:
            path = Path(scratch)
            base = path / "database.tar.gz"
            with (recovery.OPS / "base.sh").open("rb") as script:
                recovery.write_stream(base, [*COMPOSE, "exec", "-T", "timescaledb", "sh", "-s"],
                                      env=ENV, source=script)

            code, pause = issue(browser(), False, "durable-field-pause")
            require(code == 202 and pause["version"] > 1, "Expected a newer pause on the surviving field")
            eventually("the field simulator to apply the pause",
                       lambda: sensor_state() == f"f|{pause['version']}")

            # Restore only the database. The field broker, gateway and simulator stay running.
            compose("stop", "backend", "timescaledb")
            image = recovery.image(container("timescaledb"))
            recovery.seed_database(restored_project, image, base)
            override = path / "restore.yaml"
            override.write_text(f"volumes:\n  database:\n    name: {restored_volume}\n    external: true\n")
            postgres_volume(override)
            set_hold(True, override)
            require(container("simulator") == survivor and container("gateway") == gateway,
                    "Cloud recovery replaced a field container")
            require(database(f"SELECT count(*) FROM registry.commands WHERE command_id='{pause['commandId']}'") == "0",
                    "Restored cloud database did not predate the field pause")
            code, problem = issue(browser(), True, "control-during-restore")
            require(code == 503 and problem["code"] == "RECOVERY_HOLD", "Restore hold was bypassed")

            generation = run(sys.executable, "scripts/recovery.py", "private-link", "reconcile-field").stdout.strip()
            floor = int(database(
                f"SELECT version_floor FROM registry.restore_generations WHERE generation_id='{generation}'"
            ))
            require(floor > pause["version"] and sensor_state() == f"f|{floor}",
                    "Cloud did not record the surviving pause and newer generation before reopening")
            require(issue(browser(), True, "control-still-held")[0] == 503,
                    "Reconciliation opened control implicitly")

            mqtt = read_field()
            ack = (b"\x08\x01" + field(0x12, old_command) + field(0x1A, SENSOR)
                   + b"\x20\x01\x28\x01\x30" + varint(old_apply))
            stale = sampling_command(SENSOR, 1, True, int(time.time() * 1000) + 30_000)
            mqtt.publish_packet(TOPIC + "/command-ack", ack, "device-a")
            mqtt.publish_packet(TOPIC + "/command", stale, "gateway-a")
            eventually("the survivor to report that it is still paused",
                       lambda: mqtt.paused_heartbeat(pause["version"]))
            require(database(f"SELECT ack_received_at IS NULL FROM registry.commands "
                             f"WHERE command_id='{old_command}'") == "t",
                    "A late acknowledgment bypassed the restore hold")

            set_hold(False, override)
            eventually("the late acknowledgment to be audited without changing Expired",
                       lambda: database(f"SELECT status,actual_enabled FROM registry.commands "
                                        f"WHERE command_id='{old_command}'") == "expired|t")
            require(sensor_state() == f"f|{floor}", "Late ACK overwrote reconciled field state")
            code, enable = issue(browser(), True, "first-post-restore-control")
            require(code == 202 and enable["version"] > floor, "New command did not advance the floor")
            eventually("the field to apply the first new command over the private link",
                       lambda: sensor_state() == f"t|{enable['version']}")
            require(container("simulator") == survivor and container("gateway") == gateway,
                    "Recovery restarted the field simulator or gateway")
            RESTORE_REPORT.parent.mkdir(parents=True, exist_ok=True)
            RESTORE_REPORT.write_text(json.dumps({
                "field_pause_version": pause["version"], "restored_version_floor": floor,
                "new_command_version": enable["version"], "field_containers_survived": True,
                "control_held_until_reconciled": True, "stale_command_ignored": True,
                "expired_status_preserved_with_late_ack": True,
            }, indent=2) + "\n")
            print(RESTORE_REPORT)
    finally:
        compose("down", "--volumes", check=False)
        run("docker", "volume", "rm", restored_volume, check=False)


if __name__ == "__main__":
    run_drill()
