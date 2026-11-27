#!/usr/bin/env python3
"""Exercise the isolated field/cloud profile using the normal stack images."""

import json
import os
import re
import subprocess
from collections import Counter
from pathlib import Path
from runpy import run_path

ROOT = Path(__file__).resolve().parents[2]
os.chdir(ROOT)
PROJECT = "iot-platform-private-link"
STATE = ROOT / "run-artifacts/private-link"
STORAGE = STATE / "storage"
MARKER = STORAGE / ".iot-edge-storage"
REPORT = ROOT / "build/ci/private-link.json"
LOG = REPORT.with_suffix(".log")
COMPOSE = [
    "docker", "compose", "--env-file", ".env.private-link", "-p", PROJECT, "--profile", "test",
    "-f", "compose.yaml", "-f", "compose.private-link.yaml",
]
ENV = dict(os.environ, IOT_PRIVATE_LINK_STATE=str(STATE), IOT_EDGE_STORAGE=str(STORAGE),
           IOT_HTTP_PORT="0", IOT_STACK_IMAGE_TAG=os.environ.get("IOT_PRIVATE_LINK_IMAGE_TAG", "test"))
helpers = run_path(str(ROOT / "scripts/ci/telemetry-faults.py"))
eventually, require, TENANTS = (helpers[key] for key in ("eventually", "require", "TENANTS"))
open_snapshot = run_path(str(ROOT / "scripts/pilot-reconcile.py"))["open_snapshot"]


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
    with open_snapshot(STORAGE / "gateway.sqlite") as sqlite:
        return {
            row for tenant in TENANTS for row in
            sqlite.execute("SELECT tenant,sensor,event FROM spool WHERE tenant=? ORDER BY id LIMIT 125", (tenant,))
        }


def queued():
    text = compose("exec", "-T", "gateway", "wget", "-qO-",
                   "http://127.0.0.1:8080/actuator/prometheus").stdout
    return {tenant: float(count) for tenant, count in re.findall(
        r'^iot_gateway_spool_events\{tenant="([^"]+)"\}\s+(\S+)', text, re.MULTILINE
    )}


def confirm(keys):
    records = database("SELECT tenant_id,sensor_id,event_id FROM telemetry.event_receipts")
    return keys <= {tuple(row.split("|")) for row in records.splitlines()}


def registry_http_status():
    result = compose("exec", "-T", "gateway", "wget", "-S", "-T", "5", "-O", "/dev/null",
                     "http://edge-listener:8081/internal/v1/registry", check=False)
    match = re.search(r"HTTP/\S+ (\d+)", result.stderr)
    return int(match[1]) if match else None


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

        edge_proxy = container("edge-uplink")
        # Keep the gateway's network namespace stable while the remote endpoint is unreachable.
        compose("stop", "cloud-uplink")
        eventually("the gateway route to report loss of the cloud", lambda: registry_http_status() in (502, 504))
        before_cut_sample = queued()

        def both_tenants_buffered():
            counts = queued()
            return all(counts.get(tenant, 0) >= before_cut_sample.get(tenant, 0) + 8 for tenant in TENANTS)

        eventually("gateway buffering for both tenants after WAN cut", both_tenants_buffered)
        frozen = commits()
        compose("stop", "gateway")
        saved = spool_keys()
        require({tenant for tenant, _, _ in saved} == set(TENANTS), "Durable spool is missing a tenant")
        sampled = Counter(tenant for tenant, _, _ in saved)

        hidden_marker = MARKER.with_suffix(".disabled")
        MARKER.rename(hidden_marker)
        try:
            compose("up", "-d", "--no-deps", "--no-build", "--force-recreate", "gateway")
            eventually("storage preflight to reject the invalid mount", lambda: "missing, uninitialized"
                       in compose("logs", "gateway").stdout)
        finally:
            hidden_marker.rename(MARKER)

        compose("up", "-d", "--no-deps", "--no-build", "--force-recreate", "--wait", "gateway")
        def both_queues_survive():
            counts = queued()
            return all(counts.get(tenant, 0) >= sampled[tenant] for tenant in TENANTS)

        eventually("both durable queues after restart with the WAN still cut", both_queues_survive)
        require(frozen == commits(), "Cloud kept receiving readings during the WAN cut")
        compose("start", "cloud-uplink")
        eventually("the authenticated TLS link and backend route to return", lambda:
                   registry_http_status() == 401)
        eventually("recorded identities in the cloud receipt ledger", lambda: confirm(saved), timeout=65)
        require(container("edge-uplink") == edge_proxy, "Replay replaced the field uplink proxy")
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(json.dumps({
            "image_architecture": arch, "unauthenticated_cloud_rejected": True,
            "cloud_route_allowlist_verified": True, "storage_preflight_verified": True,
            "gateway_recreated_during_wan_outage": True, "replayed_identities": len(saved),
            "tenants": len(TENANTS),
        }, indent=2) + "\n")
        print(REPORT)
    finally:
        LOG.parent.mkdir(parents=True, exist_ok=True)
        LOG.write_text(compose("logs", "--no-color", "--tail", "120", "edge-uplink", "cloud-uplink",
                               "gateway", "backend", check=False).stdout)
        compose("down", "--volumes", check=False)


if __name__ == "__main__":
    verify()
