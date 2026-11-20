"""Verify that each nightly mutation scope actually ran tests before publishing reports."""

from collections import Counter
from pathlib import Path
from xml.etree import ElementTree

PROJECT = Path(__file__).resolve().parents[2]


def summarize(module):
    report = PROJECT / module / "build/reports/pitest"
    entries = ElementTree.parse(report / "mutations.xml").getroot().findall("mutation")
    if not (report / "index.html").is_file() or not entries:
        raise SystemExit(f"{module} did not produce PIT HTML and XML mutation reports.")
    if not any(int(entry.attrib.get("numberOfTestsRun", 0)) for entry in entries):
        raise SystemExit(f"{module} did not execute any mutation tests.")
    statuses = Counter(entry.attrib["status"] for entry in entries)
    summary = ", ".join(f"{state.lower()} {count}" for state, count in sorted(statuses.items()))
    print(f"{module} · {len(entries)} mutations · {summary}")


if __name__ == "__main__":
    for module in ("backend", "contracts", "gateway", "simulator"):
        summarize(module)
