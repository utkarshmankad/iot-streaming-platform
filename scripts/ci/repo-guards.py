import json
import subprocess
from pathlib import Path

for name in ("specs.md", "architecture.md", "plan.md"):
    content = Path(name).read_text()
    assert content.startswith("# "), f"Missing document title: {name}"
    assert "\n## Change history\n" in content, f"Missing change history: {name}"

ruleset = json.loads(Path(".github/rulesets/main.json").read_text())
assert ruleset["enforcement"] == "active"
assert ruleset["bypass_actors"] == []
rules = {rule["type"]: rule.get("parameters", {}) for rule in ruleset["rules"]}
assert {"deletion", "non_fast_forward", "required_linear_history"} <= rules.keys()
assert rules["pull_request"]["allowed_merge_methods"] == ["squash"]
assert rules["pull_request"]["required_review_thread_resolution"]
required = rules["required_status_checks"]
assert required["strict_required_status_checks_policy"]
assert any(check["context"] == "CI gate" for check in required["required_status_checks"])

tracked = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
data_dirs = {"backups", "captures", "logs", "run-artifacts", "telemetry-dump", "private-data"}
data_extensions = {".pcap", ".pcapng", ".db", ".dump", ".pem", ".key"}
for name in filter(None, tracked):
    path = Path(name)
    if (
        data_dirs.intersection(path.parts[:-1])
        or (path.name.startswith(".env") and path.name != ".env.example")
        or path.suffix in data_extensions
        or ".sqlite" in path.name
    ):
        raise SystemExit(f"Private input, credential file, or run output is tracked: {name}")
