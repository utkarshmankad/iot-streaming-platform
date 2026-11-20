"""Exercise real browser API access while an isolated recovery is held and resumed."""
import sys
import time

from browser_api import Browser

credentials, port, stage = sys.argv[1:]
browser = Browser(credentials, port)
code, registry = browser.call("/sensors?limit=1")
assert code == 200 and registry["items"], "Restored tenant registry is unreadable"
sensor = registry["items"][0]["sensorId"]
code, command = browser.post(f"/sensors/{sensor}/commands", {"enabled": False},
                             {"Idempotency-Key": "recovery-drill"})
if stage == "hold":
    assert code == 503 and command["code"] == "RECOVERY_HOLD", "Control hold was bypassed"
else:
    assert code == 202 and command["version"] > 77, "Restored commands did not use the new generation"
    for _ in range(25):
        code, status = browser.call(f"/commands/{command['commandId']}")
        assert code == 200, "Restored command state could not be read"
        if status["status"] == "applied" and status["confirmedEnabled"] is False:
            break
        time.sleep(0.5)
    else:
        raise AssertionError("The simulator did not apply a command from the new generation")
