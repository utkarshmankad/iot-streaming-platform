#!/usr/bin/env python3
"""Exercise the isolated field/cloud profile using the normal stack images."""

import json
import os
import sqlite3
import subprocess
from contextlib import closing
from pathlib import Path
from runpy import run_path

ROOT = Path(__file__).resolve().parents[2]
os.chdir(ROOT)
PROJECT = "iot-platform-private-link"
STATE = ROOT / "run-artifacts/private-link"
STORAGE = STATE / "storage"
MARKER = STORAGE / ".iot-edge-storage"
REPORT = ROOT / "build/ci/private-link.json"
COMPOSE = [
    "docker", "compose", "--env-file", ".env.private-link", "-p", PROJECT, "--profile", "test",
    "-f", "compose.yaml", "-f", "compose.private-link.yaml",
]
ENV = dict(os.environ, IOT_PRIVATE_LINK_STATE=str(STATE), IOT_EDGE_STORAGE=str(STORAGE),
           IOT_HTTP_PORT="0", IOT_STACK_IMAGE_TAG=os.environ.get("IOT_PRIVATE_LINK_IMAGE_TAG", "test"))
helpers = run_path(str(ROOT / "scripts/ci/telemetry-faults.py"))
eventually, require, TENANTS = (helpers[key] for key in ("eventually", "require", "TENANTS"))


def run(*args, check=True, capture=True):
    return subprocess.run(args, check=check, capture_output=capture, env=ENV, timeout=240, text=True)


def compose(*args, check=True):
    result = run(*COMPOSE, *args, check=False)
    if check and result.returncode:
        raise RuntimeError(f"Compose {args[0]} failed: {result.stderr.strip()}")
    return result


def database(query):
    return compose("exec", "-T", "timescaledb", "psql", "-XqAt", "-U", "iot", "-d", "iot",
                   "-v", "ON_ERROR_STOP=1", "-c", query).stdout.strip()


def commits():
    return {tenant: int(count) for tenant, count in (
        row.split("|") for row in database(
            "SELECT tenant_id, count(*) FROM telemetry.readings GROUP BY tenant_id"
        ).splitlines()
    )}


def container(service):
    return compose("ps", "-a", "-q", service).stdout.strip()


def spool_keys():
    with closing(sqlite3.connect(f"{(STORAGE / 'gateway.sqlite').as_uri()}?mode=ro", uri=True)) as sqlite:
        return set(sqlite.execute("SELECT tenant,sensor,event FROM spool ORDER BY id LIMIT 250"))


def queued():
    text = compose("exec", "-T", "gateway", "wget", "-qO-",
                   "http://127.0.0.1:8080/actuator/prometheus").stdout
    return sum(float(line.split()[1]) for line in text.splitlines()
               if line.startswith("iot_gateway_spool_events{"))


def confirm(keys):
    records = database("SELECT tenant_id,sensor_id,event_id FROM telemetry.event_receipts")
    return keys <= {tuple(row.split("|")) for row in records.splitlines()}


def verify():
    require(not run("docker", "ps", "-aq", "--filter",
                    f"label=com.docker.compose.project={PROJECT}").stdout.strip(),
            "Private-link drill needs a fresh disposable Compose project")
    require(not run("docker", "volume", "ls", "-q", "--filter",
                    f"label=com.docker.compose.project={PROJECT}").stdout.strip(),
            "Private-link drill found existing project volumes")
    require(not STATE.exists(), "Private-link drill needs an unused fixture storage directory")
    try:
        run("./scripts/stack.sh", "private-link", "up-built", capture=False)
        config = json.loads(compose("config", "--format", "json").stdout)["services"]
        require(set(config["gateway"]["networks"]) == {"sensors", "edge-api"},
                "Gateway can bypass the field proxy")
        require(set(config["backend"]["networks"]) == {"gateway-api", "database", "dashboard"},
                "Backend unexpectedly participates in the WAN or field network")
        require(all(not cfg.get("ports") for name, cfg in config.items() if name != "frontend"),
                "An internal service publishes a host port")
        require({name for name, cfg in config.items() if "wan" in (cfg.get("networks") or {})}
                == {"edge-uplink", "cloud-uplink"}, "Unexpected service on the WAN")
        image = config["gateway"]["image"]
        arch = run("docker", "image", "inspect", image, "--format", "{{.Architecture}}").stdout.strip()

        eventually("telemetry from both tenants over the private link", lambda: set(commits()) == set(TENANTS))
        result = compose("exec", "-T", "edge-uplink", "wget", "-S", "--no-check-certificate", "-O",
                         "/dev/null", "https://cloud-uplink:8443/internal/v1/registry", check=False)
        require(result.returncode and "400 Bad Request" in result.stderr,
                "Cloud ingress did not reject a caller without the client certificate")
        result = compose("exec", "-T", "cloud-uplink", "wget", "-S", "-T", "3", "-O",
                         "/dev/null", "http://edge-uplink:8081/", check=False)
        require(result.returncode and "HTTP/" not in result.stderr,
                "Field-only HTTP listener is accessible on the WAN")
        result = compose("exec", "-T", "gateway", "wget", "-S", "-O", "/dev/null",
                         "http://edge-listener:8081/internal/v1/other", check=False)
        require(result.returncode and "404 Not Found" in result.stderr,
                "TLS-authenticated proxy exposes an unexpected route")

        uplink = container("edge-uplink")
        run("docker", "network", "disconnect", f"{PROJECT}_wan", uplink)
        eventually("gateway buffering after WAN cut", lambda: queued() >= 16)
        frozen = commits()
        compose("stop", "gateway")
        saved = spool_keys()
        require({tenant for tenant, _, _ in saved} == set(TENANTS), "Durable spool is missing a tenant")

        hidden_marker = MARKER.with_suffix(".disabled")
        MARKER.rename(hidden_marker)
        try:
            compose("up", "-d", "--no-deps", "--no-build", "--force-recreate", "gateway")
            eventually("storage preflight to reject the invalid mount", lambda: "missing, uninitialized"
                       in compose("logs", "gateway").stdout)
        finally:
            hidden_marker.rename(MARKER)

        compose("up", "-d", "--no-deps", "--no-build", "--force-recreate", "--wait", "gateway")
        eventually("durable queue after restart with the WAN still cut", lambda: queued() >= len(saved))
        require(frozen == commits(), "Cloud kept receiving readings during the WAN cut")
        run("docker", "network", "connect", f"{PROJECT}_wan", uplink)
        eventually("recorded identities in the cloud receipt ledger", lambda: confirm(saved), timeout=65)
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(json.dumps({
            "image_architecture": arch, "unauthenticated_cloud_rejected": True,
            "cloud_route_allowlist_verified": True, "storage_preflight_verified": True,
            "gateway_recreated_during_wan_outage": True, "replayed_identities": len(saved),
            "tenants": len(TENANTS),
        }, indent=2) + "\n")
        print(REPORT)
    finally:
        compose("down", "--volumes", check=False)


if __name__ == "__main__":
    verify()
