#!/usr/bin/env python3
"""Back up and recover the local Compose stack as a Docker platform operator."""
import argparse
import csv
import fcntl
import hashlib
import io
from contextlib import closing
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from observe import backup_status

ROOT = Path(__file__).resolve().parents[1]
OPS = ROOT / "ops/recovery"
MIN_FREE = 5_000_000_000
BACKUP_FILES = ("database.tar.gz", "gateway.sqlite", "simulator.sqlite", "stack.env", "compose.yaml")
QUEUES = {"gateway": ("gateway-spool", "gateway.sqlite"), "simulator": ("simulator-outbox", "simulator.sqlite")}
BACKUP_ID = re.compile(r"[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}")
PROJECT_SUFFIX = re.compile(r"[a-z0-9][a-z0-9-]{0,27}")


class RecoveryError(RuntimeError):
    pass


def now():
    return datetime.now(timezone.utc)


def run(args, *, env=None, source=None, sink=None, timeout=120):
    return subprocess.run(
        args, cwd=ROOT, env=env, stdin=source, stdout=sink or subprocess.PIPE,
        check=True, timeout=timeout,
    ).stdout


def output(args, **kwargs):
    return run(args, **kwargs).decode().strip()


def sync_dir(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic(path, text):
    with tempfile.NamedTemporaryFile(dir=path.parent, mode="w", encoding="utf-8", delete=False) as staging:
        staging.write(text)
        staging.flush()
        os.fsync(staging.fileno())
    Path(staging.name).replace(path)
    sync_dir(path.parent)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as reader:
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_stream(path, args, *, env=None, source=None):
    with path.open("xb") as stream:
        run(args, env=env, source=source, sink=stream, timeout=14400)
        stream.flush()
        os.fsync(stream.fileno())


def setup_target(profile, target):
    target = target.resolve(strict=True)
    if not target.is_dir() or "\n" in str(target) or "\r" in str(target):
        raise RecoveryError("Use an existing local backup directory.")
    if profile == "local" and target.stat().st_dev == ROOT.stat().st_dev:
        raise RecoveryError("Use an independently mounted encrypted filesystem for local backups.")
    if target.stat().st_mode & 0o077:
        raise RecoveryError("Restrict the host backup directory to its owner (mode 0700).")
    archive = target / "wal"
    archive.mkdir(mode=0o700, exist_ok=True)
    if archive.is_symlink():
        raise RecoveryError("Use a real directory for the WAL archive.")
    # PostgreSQL and the laptop user have different UIDs. The host parent is private.
    archive.chmod(0o1777)
    marker = archive / ".iot-target-id"
    if not marker.exists():
        atomic(marker, uuid.uuid4().hex + "\n")
        marker.chmod(0o444)
    token = marker.read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{32}", token):
        raise RecoveryError("Invalid backup mount identity.")
    atomic(ROOT / f".env.backup-{profile}", f"{target}\n{token}\n")
    print("Backup target configured. Recreate the source stack to enable WAL archival.")


def backup_target(profile):
    try:
        target, token = (ROOT / f".env.backup-{profile}").read_text().splitlines()
        target = Path(target)
        online = (target / "wal/.iot-target-id").read_text().strip() == token
    except (OSError, ValueError):
        raise RecoveryError("Configure and mount the backup target before continuing.") from None
    if not re.fullmatch(r"[0-9a-f]{32}", token) or not online:
        raise RecoveryError("The configured independent backup mount is unavailable.")
    return target, token


def source_stack(profile, target, token):
    env = dict(os.environ, IOT_STACK_IMAGE_TAG=profile, IOT_BACKUP_DIR=str(target),
               IOT_BACKUP_TARGET_ID=token)
    project = "iot-platform-local" if profile == "local" else "iot-platform-ci"
    credentials = ROOT / (".env.stack" if profile == "local" else ".env.test")
    return (["docker", "compose", "--env-file", str(credentials), "-p", project, "--profile",
             profile, "-f", "compose.yaml", "-f", "compose.backup.yaml"], env, credentials)


def psql(stack, env, query, timeout=120):
    return output([*stack, "exec", "-T", "timescaledb", "psql", "-XqAt", "-U", "iot", "-d", "iot",
                   "-v", "ON_ERROR_STOP=1", "-c", query], env=env, timeout=timeout)


def container(stack, env, service):
    ids = output([*stack, "ps", "--quiet", service], env=env).splitlines()
    if len(ids) != 1:
        raise RecoveryError(f"Expected one running {service} container.")
    return ids[0]


def image(container_id):
    return output(["docker", "inspect", "--format", "{{.Config.Image}}", container_id])


def await_wal(archive, segment):
    path = archive / (segment + ".gz")
    for _ in range(90):
        if path.is_file():
            return path
        time.sleep(1)
    raise RecoveryError("The checkpoint WAL segment did not reach the independent archive.")


def queue_totals(path, table):
    with closing(sqlite3.connect(f"{path.as_uri()}?mode=ro", uri=True)) as db:
        sql = {"spool": "SELECT tenant,count(*) FROM spool GROUP BY tenant",
               "pending": "SELECT tenant,count(*) FROM pending GROUP BY tenant"}[table]
        return dict(db.execute(sql))


def snapshot_queues(stack, env, receiver, staging):
    captured = {}
    for service, (_, name) in QUEUES.items():
        source_id = container(stack, env, service)
        args = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "-i", "--entrypoint", "python",
                "--user", "10001:10001", "--volumes-from", source_id, receiver, "-", f"/var/lib/iot/{name}"]
        with (OPS / "snapshot.py").open("rb") as script:
            write_stream(staging / name, args, source=script)
        captured[service] = now().isoformat()
    return captured


def take(profile):
    target, token = backup_target(profile)
    if shutil.disk_usage(target).free < MIN_FREE:
        raise RecoveryError("The independent backup target has less than 5 GB free.")
    stack, env, credentials = source_stack(profile, target, token)
    if psql(stack, env, "SHOW archive_mode") != "on":
        raise RecoveryError("Recreate the source stack with its configured WAL archive first.")
    db_image = image(container(stack, env, "timescaledb"))
    observer_image = image(container(stack, env, "alarm-receiver"))
    started = now()
    backup_id = started.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    checkpoint = "iot_backup_" + backup_id
    versions = json.loads(psql(stack, env, """
        SELECT json_build_object(
          'system_id', (pg_control_system()).system_identifier::text,
          'server_version', current_setting('server_version_num'),
          'timescaledb', (SELECT extversion FROM pg_extension WHERE extname='timescaledb'),
          'schema', (SELECT max(installed_rank) FROM registry.flyway_schema_history WHERE success)
        )"""))
    with tempfile.TemporaryDirectory(dir=target, prefix=".incomplete-") as scratch:
        staging = Path(scratch)
        with (OPS / "base.sh").open("rb") as script:
            write_stream(staging / "database.tar.gz",
                         [*stack, "exec", "-T", "timescaledb", "sh", "-s"], env=env, source=script)
        base_finished = now()
        captured = snapshot_queues(stack, env, observer_image, staging)
        with credentials.open("rb") as config, (staging / "stack.env").open("xb") as dest:
            shutil.copyfileobj(config, dest)
            dest.flush()
            os.fsync(dest.fileno())
        with (staging / "compose.yaml").open("xb") as dest:
            dest.write((ROOT / "compose.yaml").read_bytes())
            dest.flush()
            os.fsync(dest.fileno())
        segment = psql(stack, env, f"SELECT pg_walfile_name(pg_create_restore_point('{checkpoint}'))")
        if not re.fullmatch(r"[A-F0-9]{24}", segment):
            raise RecoveryError("PostgreSQL returned an invalid checkpoint segment.")
        checkpoint_at = now()
        psql(stack, env, "SELECT pg_switch_wal()")
        wal = await_wal(target / "wal" / versions["system_id"], segment)
        manifest = {
            "format": 1, "id": backup_id, "created_utc": started.isoformat(),
            "base_finished_utc": base_finished.isoformat(), "checkpoint_utc": checkpoint_at.isoformat(),
            "verified_utc": now().isoformat(), "checkpoint": checkpoint, "source_profile": profile,
            "database_image": db_image, "observer_image": observer_image, "database": versions,
            "repository_revision": output(["git", "rev-parse", "HEAD"]),
            "repository_dirty": bool(output(["git", "status", "--porcelain", "--untracked-files=no"])),
            "queue_snapshot_utc": captured,
            "checkpoint_wal": {"name": wal.name, "sha256": sha256(wal)},
            "queued": {
                "gateway": queue_totals(staging / "gateway.sqlite", "spool"),
                "simulator": queue_totals(staging / "simulator.sqlite", "pending"),
            },
            "files": {name: {"sha256": sha256(staging / name), "bytes": (staging / name).stat().st_size}
                      for name in BACKUP_FILES},
        }
        atomic(staging / "manifest.json", json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        staging.rename(target / backup_id)
        sync_dir(target)
    backup_status(profile, int(now().timestamp()), False)
    print(backup_id)
    return backup_id


def verify(target, backup_id):
    if not BACKUP_ID.fullmatch(backup_id):
        raise RecoveryError("Invalid backup ID.")
    folder = target / backup_id
    manifest_path = folder / "manifest.json"
    if folder.is_symlink() or manifest_path.is_symlink():
        raise RecoveryError("Verify the original backup directory and manifest.")
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("format") != 1 or manifest.get("id") != backup_id:
        raise RecoveryError("Backup manifest identity or format does not match.")
    for name in BACKUP_FILES:
        expected = manifest["files"][name]
        path = folder / name
        if path.is_symlink() or path.stat().st_size != expected["bytes"] or sha256(path) != expected["sha256"]:
            raise RecoveryError(f"Backup checksum failed for {name}.")
    system_id = manifest["database"]["system_id"]
    segment = manifest["checkpoint_wal"]["name"]
    if not re.fullmatch(r"[0-9]+", system_id) or not re.fullmatch(r"[A-F0-9]{24}\.gz", segment):
        raise RecoveryError("Invalid manifest WAL identity.")
    wal = target / "wal" / system_id / segment
    if wal.is_symlink() or sha256(wal) != manifest["checkpoint_wal"]["sha256"]:
        raise RecoveryError("Backup checkpoint WAL checksum failed.")
    return folder, manifest, wal.parent


def project_name(name):
    if not PROJECT_SUFFIX.fullmatch(name):
        raise RecoveryError("Use a short lowercase restore name with hyphens.")
    return "iot-restore-" + name


def restore_stack(profile, project, folder, archive, port, *, hold=True):
    env = dict(os.environ, IOT_STACK_IMAGE_TAG=profile, IOT_RESTORE_WAL=str(archive),
               IOT_CONTROL_RESTORE_HOLD=str(hold).lower(), IOT_HTTP_PORT=str(port))
    return (["docker", "compose", "--env-file", str(folder / "stack.env"), "-p", project, "--profile",
             profile, "-f", "compose.yaml", "-f", "compose.recovery.yaml"], env)


def new_project(project):
    label = f"label=com.docker.compose.project={project}"
    resources = (
        ["docker", "ps", "-aq", "--filter", label],
        ["docker", "volume", "ls", "-q", "--filter", label],
        ["docker", "network", "ls", "-q", "--filter", label],
    )
    if any(output(args) for args in resources):
        raise RecoveryError("This restore name already has resources. Choose a fresh restore name.")


def new_volume(project, name):
    volume = project + "_" + name
    existing = output(["docker", "volume", "ls", "--format", "{{.Name}}"]).splitlines()
    if volume in existing:
        raise RecoveryError("A restore volume with the same name already exists.")
    run(["docker", "volume", "create", "--label", f"com.docker.compose.project={project}",
         "--label", f"com.docker.compose.volume={name}", volume])
    return volume


def validate_tar(path):
    with tarfile.open(path, "r|gz") as archive:
        for member in archive:
            entry = PurePosixPath(member.name)
            if entry.is_absolute() or ".." in entry.parts or not (member.isreg() or member.isdir()):
                raise RecoveryError("The base backup has an unsafe or unsupported tar member.")


def seed_database(project, db_image, base):
    validate_tar(base)
    volume = new_volume(project, "database")
    with base.open("rb") as stream:
        run(["docker", "run", "--rm", "--pull", "never", "-i", "--network", "none", "--user", "0:0", "-v",
             volume + ":/restored", "--entrypoint", "/bin/sh", db_image, "-ec",
             "tar -xzf - -C /restored && chown -R postgres:postgres /restored && chmod 700 /restored"],
            source=stream, timeout=14400)
    return volume


def seed_queues(project, receiver, folder):
    for service, (name, filename) in QUEUES.items():
        volume = new_volume(project, name)
        script = (OPS / "seed-queue.py").read_text()
        with (folder / filename).open("rb") as stream:
            run(["docker", "run", "--rm", "--pull", "never", "-i", "--network", "none", "--user", "0:0", "-v",
                 volume + ":/restored", "--entrypoint", "python", receiver,
                 "-c", script, filename, service], source=stream, timeout=14400)


def recovery_settings(db_image, volume, target):
    command = """
set -eu
umask 077
rm -f /restored/standby.signal
cat >> /restored/postgresql.auto.conf
chown postgres:postgres /restored/postgresql.auto.conf
touch /restored/recovery.signal
chown postgres:postgres /restored/recovery.signal
"""
    options = ("\nrestore_command = '/bin/sh /run/restore-wal.sh \"%f\" \"%p\"'\n"
               "recovery_target_timeline = 'current'\nrecovery_target_action = 'promote'\n"
               f"{target}\n")
    with tempfile.TemporaryFile() as source:
        source.write(options.encode())
        source.seek(0)
        run(["docker", "run", "--rm", "--pull", "never", "-i", "--network", "none", "--user", "0:0", "-v",
             volume + ":/restored", "--entrypoint", "/bin/sh", db_image, "-ec", command], source=source)


def parse_time(value):
    try:
        date = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if date.tzinfo is None:
            raise ValueError("time zone required")
        return date.astimezone(timezone.utc)
    except (AttributeError, ValueError):
        raise RecoveryError("Use an ISO 8601 recovery time with a time zone.") from None


def reconcile(stack, env, folder):
    generation = str(uuid.uuid4())
    with closing(sqlite3.connect(f"{(folder / 'simulator.sqlite').as_uri()}?mode=ro", uri=True)) as snapshot:
        rows = snapshot.execute("SELECT tenant,sensor,version,enabled FROM sampling_state")
        data = io.StringIO()
        csv.writer(data).writerows((tenant, sensor, version, bool(enabled)) for tenant, sensor, version, enabled in rows)
    with tempfile.TemporaryFile() as source:
        source.write(data.getvalue().encode())
        source.seek(0)
        run([*stack, "exec", "-T", "timescaledb", "sh", "-ec",
             "umask 077; cat > /tmp/iot-recovery-state.csv"], env=env, source=source)
    with (OPS / "reconcile.sql").open("rb") as source:
        run([*stack, "exec", "-T", "timescaledb", "psql", "-Xq", "-U", "iot", "-d", "iot",
             "-v", "generation=" + generation, "-f", "-"], env=env, source=source)
    return generation


def import_queued_ids(stack, env, folder):
    psql(stack, env, """
        CREATE SCHEMA iot_recovery;
        REVOKE ALL ON SCHEMA iot_recovery FROM PUBLIC;
        CREATE TABLE iot_recovery.queue_ids (
          source text NOT NULL, tenant_id uuid NOT NULL, sensor_id uuid NOT NULL, event_id uuid NOT NULL,
          PRIMARY KEY (source,tenant_id,sensor_id,event_id));
    """)
    args = [*stack, "exec", "-T", "timescaledb", "psql", "-Xq", "-U", "iot", "-d", "iot",
            "-v", "ON_ERROR_STOP=1", "-c", "COPY iot_recovery.queue_ids FROM STDIN WITH (FORMAT csv)"]
    with subprocess.Popen(args, cwd=ROOT, env=env, stdin=subprocess.PIPE, text=True) as client:
        with client.stdin as stream:
            writer = csv.writer(stream)
            inputs = {"gateway": "SELECT tenant,sensor,event FROM spool",
                      "simulator": "SELECT tenant,sensor,event FROM pending"}
            for service, (_, filename) in QUEUES.items():
                path = folder / filename
                with closing(sqlite3.connect(f"{path.as_uri()}?mode=ro", uri=True)) as snapshot:
                    writer.writerows((service, *key) for key in snapshot.execute(inputs[service]))
        if client.wait(timeout=14400):
            raise RecoveryError("Could not load the recorded queue identities.")


def reconcile_ledger(stack, env):
    inventory = json.loads(psql(stack, env, """
        WITH readings AS (
          SELECT tenant_id,count(*) AS readings,max(observed_at) AS newest
          FROM telemetry.readings GROUP BY tenant_id
        ), receipts AS (
          SELECT tenant_id,count(*) AS receipts FROM telemetry.event_receipts GROUP BY tenant_id
        )
        SELECT coalesce(json_agg(json_build_object('tenant',t.tenant_id,
            'readings',coalesce(r.readings,0),'receipts',coalesce(e.receipts,0),'newest',r.newest)),'[]')
        FROM registry.tenants t LEFT JOIN readings r USING (tenant_id) LEFT JOIN receipts e USING (tenant_id)
        WHERE t.status='active';
    """, timeout=7200))
    if not inventory or any(row["readings"] != row["receipts"] for row in inventory):
        raise RecoveryError("Restored reading and receipt counts disagree or tenant inventory is empty.")
    for row in inventory:
        tenant = str(uuid.UUID(row["tenant"]))
        visible = psql(stack, env, f"""
            BEGIN;
            SET LOCAL ROLE iot_app;
            SET LOCAL iot.tenant_id = '{tenant}';
            SELECT NOT EXISTS (SELECT 1 FROM telemetry.event_receipts WHERE tenant_id<>'{tenant}');
            ROLLBACK;
        """)
        if visible != "t":
            raise RecoveryError("Restored row-level tenant isolation failed.")
    return inventory


def queue_replay_status(stack, env):
    return json.loads(psql(stack, env, """
        WITH identities AS (
          SELECT DISTINCT tenant_id,sensor_id,event_id FROM iot_recovery.queue_ids
        ), reconciliation AS (
          SELECT q.tenant_id,count(*) AS recorded,count(r.event_id) AS committed
          FROM identities q LEFT JOIN telemetry.event_receipts r USING (tenant_id,sensor_id,event_id)
          GROUP BY q.tenant_id
        )
        SELECT coalesce(json_agg(json_build_object(
          'tenant',tenant_id,'recorded',recorded,'committed',committed,
          'unconfirmed',recorded-committed)),'[]') FROM reconciliation;
    """, timeout=3600))


def write_report(report_path, report):
    report_path.parent.mkdir(parents=True, exist_ok=True)
    atomic(report_path, json.dumps(report, indent=2, sort_keys=True) + "\n")


def restore(profile, backup_id, name, target_time, port):
    target, _ = backup_target(profile)
    folder, manifest, archive = verify(target, backup_id)
    if manifest["source_profile"] != profile:
        raise RecoveryError("Select a backup from the same source profile.")
    if manifest["observer_image"] != "iot-platform-alarm-receiver:" + profile:
        raise RecoveryError("Use a backup created by the expected local receiver image.")
    if target_time:
        stamp = parse_time(target_time)
        if stamp <= parse_time(manifest["base_finished_utc"]) or stamp > now():
            raise RecoveryError("Choose a time after the base backup finished and at or before the current time.")
        target_setting = f"recovery_target_time = '{stamp.isoformat()}'"
    else:
        checkpoint = manifest["checkpoint"]
        if checkpoint != "iot_backup_" + backup_id:
            raise RecoveryError("The named checkpoint does not match the backup.")
        target_setting = f"recovery_target_name = '{checkpoint}'"
    project = project_name(name)
    stack, env = restore_stack(profile, project, folder, archive, port)
    pinned = [item for item in output([*stack, "config", "--images"], env=env).splitlines()
              if item.startswith("timescale/timescaledb:")]
    if not pinned or set(pinned) != {manifest["database_image"]}:
        raise RecoveryError("Use a checkout pinned to the manifest's database image.")
    new_project(project)
    location = ROOT / "run-artifacts" / project / "restore.json"
    if location.exists():
        raise RecoveryError("A report already exists for this restore name.")
    started = time.monotonic()
    volume = seed_database(project, manifest["database_image"], folder / "database.tar.gz")
    recovery_settings(manifest["database_image"], volume, target_setting)
    seed_queues(project, manifest["observer_image"], folder)
    run([*stack, "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "240",
         "timescaledb"], env=env, timeout=300)
    for _ in range(120):
        try:
            if psql(stack, env, "SELECT NOT pg_is_in_recovery()", timeout=5) == "t":
                break
        except (subprocess.SubprocessError, OSError):
            pass
        time.sleep(2)
    else:
        raise RecoveryError("The chosen PostgreSQL recovery target was not reached.")
    database = json.loads(psql(stack, env, """
        SELECT json_build_object(
          'system_id', (pg_control_system()).system_identifier::text,
          'server_version', current_setting('server_version_num'),
          'timescaledb', (SELECT extversion FROM pg_extension WHERE extname='timescaledb'),
          'schema', (SELECT max(installed_rank) FROM registry.flyway_schema_history WHERE success),
          'last_replayed_utc', pg_last_xact_replay_timestamp());"""))
    if any(database[key] != manifest["database"][key] for key in ("system_id", "server_version", "timescaledb", "schema")):
        raise RecoveryError("The restored cluster identity, version or schema differs from the manifest.")
    generation = reconcile(stack, env, folder)
    import_queued_ids(stack, env, folder)
    inventory = reconcile_ledger(stack, env)
    run([*stack, "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "120",
         "backend", "frontend"], env=env, timeout=150)
    report = {
        "backup": backup_id, "source_profile": profile, "project": project, "port": port,
        "target": target_time or manifest["checkpoint"], "hold": True,
        "generation": generation, "database": database, "tenant_inventory": inventory,
        "snapshot_queued": manifest["queued"], "queue_snapshot_utc": manifest["queue_snapshot_utc"],
        "restored_utc": now().isoformat(), "seconds_to_queryable": round(time.monotonic()-started, 3),
        "queues_before_replay": queue_replay_status(stack, env),
    }
    write_report(location, report)
    print(location)


def resume(profile, name, drain_seconds):
    project = project_name(name)
    location = ROOT / "run-artifacts" / project / "restore.json"
    report = json.loads(location.read_text())
    if report["project"] != project or report["source_profile"] != profile:
        raise RecoveryError("The report does not identify this isolated restore.")
    target, _ = backup_target(profile)
    folder, manifest, archive = verify(target, report["backup"])
    stack, env = restore_stack(profile, project, folder, archive, report["port"], hold=False)
    generation = str(uuid.UUID(report["generation"]))
    if psql(stack, env, f"SELECT EXISTS(SELECT 1 FROM registry.restore_generations WHERE generation_id='{generation}')") != "t":
        raise RecoveryError("The running database has not recorded this control generation.")
    started = time.monotonic()
    run([*stack, "up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", "frontend",
         "backend", "mosquitto", "gateway", "simulator"], env=env, timeout=210)
    report["hold"] = False
    while True:
        progress = queue_replay_status(stack, env)
        complete = all(row["unconfirmed"] == 0 for row in progress)
        elapsed = time.monotonic() - started
        if complete or elapsed >= drain_seconds:
            break
        time.sleep(5)
    report["queues_after_replay"] = progress
    report["seconds_to_confirm_backlog"] = round(elapsed, 3) if complete else None
    report["seconds_of_observed_replay"] = round(elapsed, 3)
    write_report(location, report)
    print(location)
    if not complete:
        raise RecoveryError("Some snapshot IDs lack receipts. See the report and reconcile rejected or missing events.")


def measure_rpo(profile, name, incident_at):
    project = project_name(name)
    location = ROOT / "run-artifacts" / project / "restore.json"
    report = json.loads(location.read_text())
    if report["source_profile"] != profile:
        raise RecoveryError("The report does not identify the selected source profile.")
    stopped = parse_time(incident_at)
    database = report["database"]["last_replayed_utc"]
    report["incident_at_utc"] = stopped.isoformat()
    captures = {**report["queue_snapshot_utc"], "database": database}
    report["rpo_seconds"] = {
        service: max(0, (stopped - parse_time(captured)).total_seconds()) if captured else None
        for service, captured in captures.items()
    }
    write_report(location, report)
    print(location)


def take_locked(profile):
    target, _ = backup_target(profile)
    with (target / ".backup.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RecoveryError("A backup for this target is already running.") from None
        return take(profile)


def watch(profile):
    last_day = None
    while True:
        try:
            target, _ = backup_target(profile)
            day = now().date()
            if day != last_day:
                found = list(target.glob(day.strftime("%Y%m%d") + "T*/manifest.json"))
                if not found:
                    take_locked(profile)
                last_day = day
        except (OSError, RecoveryError, subprocess.SubprocessError):
            print("Daily backup will retry. Check the local database and the configured archive mount.", file=sys.stderr)
        time.sleep(60)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=("local", "test"))
    actions = parser.add_subparsers(dest="action", required=True)
    actions.add_parser("configure").add_argument("target", type=Path)
    actions.add_parser("take")
    actions.add_parser("watch")
    actions.add_parser("verify").add_argument("backup")
    recover = actions.add_parser("restore")
    recover.add_argument("backup")
    recover.add_argument("name")
    recover.add_argument("--target-time")
    recover.add_argument("--port", type=int, default=18082)
    replay = actions.add_parser("resume")
    replay.add_argument("name")
    replay.add_argument("--drain-seconds", type=int, default=120)
    rpo = actions.add_parser("measure-rpo")
    rpo.add_argument("name")
    rpo.add_argument("--incident-at", required=True)
    args = parser.parse_args()
    if args.action == "configure":
        setup_target(args.profile, args.target)
    elif args.action == "take":
        take_locked(args.profile)
    elif args.action == "watch":
        watch(args.profile)
    elif args.action == "verify":
        target, _ = backup_target(args.profile)
        try:
            verify(target, args.backup)
        except (OSError, ValueError, KeyError, RecoveryError):
            backup_status(args.profile, None, True)
            raise
        print(args.backup)
    elif args.action == "restore":
        if not 1024 <= args.port <= 65535 or args.port in (8080, 18080):
            parser.error("Use a distinct, unprivileged restore port.")
        restore(args.profile, args.backup, args.name, args.target_time, args.port)
    elif args.action == "resume":
        if not 0 <= args.drain_seconds <= 259200:
            parser.error("Use a drain window between zero and three days.")
        resume(args.profile, args.name, args.drain_seconds)
    else:
        measure_rpo(args.profile, args.name, args.incident_at)


if __name__ == "__main__":
    try:
        main()
    except (OSError, KeyError, ValueError, sqlite3.Error, subprocess.SubprocessError, RecoveryError) as error:
        # Commands and Docker output may include configuration values.
        if isinstance(error, RecoveryError):
            print(str(error), file=sys.stderr)
        else:
            print(f"Recovery operation failed ({type(error).__name__}). Inspect local service status.", file=sys.stderr)
        raise SystemExit(1) from None
