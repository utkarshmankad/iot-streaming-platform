"""Exercise real browser API access while an isolated recovery is held and resumed."""
import json
import sys
import time
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener

credentials, port, stage = sys.argv[1:]
env = dict(line.split("=", 1) for line in Path(credentials).read_text().splitlines() if "=" in line)
base = f"http://127.0.0.1:{int(port)}/api/v1"
browser = build_opener(HTTPCookieProcessor(CookieJar()))


def call(path, method="GET", data=None, headers=None):
    body = json.dumps(data).encode() if data is not None else None
    request = Request(base + path, data=body, method=method,
                      headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with browser.open(request, timeout=10) as result:
            return result.status, json.load(result)
    except HTTPError as error:
        return error.code, json.load(error)


_, csrf = call("/session/csrf")
code, _ = call("/session", "POST",
               {"username": "operator-a", "password": env["IOT_STACK_OPERATOR_A_PASSWORD"]},
               {csrf["headerName"]: csrf["token"]})
assert code == 200, "Restored login failed"
code, registry = call("/sensors?limit=1")
assert code == 200 and registry["items"], "Restored tenant registry is unreadable"
sensor = registry["items"][0]["sensorId"]
_, csrf = call("/session/csrf")
code, command = call(f"/sensors/{sensor}/commands", "POST", {"enabled": False},
                     {csrf["headerName"]: csrf["token"], "Idempotency-Key": "recovery-drill"})
if stage == "hold":
    assert code == 503 and command["code"] == "RECOVERY_HOLD", "Control hold was bypassed"
else:
    assert code == 202 and command["version"] > 77, "Restored commands did not use the new generation"
    for _ in range(25):
        code, status = call(f"/commands/{command['commandId']}")
        assert code == 200, "Restored command state could not be read"
        if status["status"] == "applied" and status["confirmedEnabled"] is False:
            break
        time.sleep(0.5)
    else:
        raise AssertionError("The simulator did not apply a command from the new generation")
