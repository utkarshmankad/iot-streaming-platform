#!/usr/bin/env python3
"""Reconcile a stopped simulator's SQLite snapshot with raw readings and receipts."""

import argparse
import csv
import json
import shutil
import sqlite3
from collections import Counter, defaultdict
from contextlib import closing, contextmanager
from itertools import zip_longest
from pathlib import Path
from tempfile import TemporaryDirectory


@contextmanager
def open_snapshot(snapshot):
    snapshot = snapshot.resolve(strict=True)
    wal = snapshot.with_name(snapshot.name + "-wal")
    if not wal.exists() or wal.stat().st_size == 0:
        # A checkpointed snapshot can be opened without SQLite's sidecar files.
        with closing(sqlite3.connect(snapshot.as_uri() + "?mode=ro&immutable=1", uri=True)) as db:
            yield db
        return

    # Opening an outstanding WAL requires writable space for SQLite's shared index.
    with TemporaryDirectory(prefix="iot-reconcile-") as work:
        copied = Path(work) / snapshot.name
        shutil.copyfile(snapshot, copied)
        shutil.copyfile(wal, copied.with_name(wal.name))
        with closing(sqlite3.connect(copied.as_uri() + "?mode=ro", uri=True)) as db:
            yield db


def accepted_events(db, totals):
    for tenant, sensor, event, observed_ms, outcome in db.execute("""
        SELECT tenant,sensor,event,observed_ms,outcome FROM fault_events
        ORDER BY tenant,sensor,event
        """):
        if outcome not in ("accepted", "dropped", "rejected"):
            raise ValueError(f"Nonterminal simulator outcome for {tenant}/{sensor}/{event}: {outcome}")
        totals[tenant][outcome] += 1
        if outcome == "accepted":
            yield [tenant, sensor, event, str(observed_ms)]


def reconcile(db, readings, receipts):
    totals = defaultdict(Counter)
    expected = accepted_events(db, totals)
    for produced, reading, receipt in zip_longest(expected, readings, receipts):
        if produced is None or reading != produced or receipt != produced[:3]:
            raise ValueError(f"Terminal identity mismatch: journal={produced}, reading={reading}, receipt={receipt}")
    stats = list(db.execute("SELECT tenant,generated,accepted,dropped,rejected,pending_events FROM stats"))
    if not stats or {row[0] for row in stats} != totals.keys():
        raise ValueError("Simulator counters and journal list different tenants")
    for tenant, generated, accepted, dropped, rejected, pending in stats:
        expected_totals = Counter(accepted=accepted, dropped=dropped, rejected=rejected)
        if (pending or generated != sum(expected_totals.values())
                or any(totals[tenant][outcome] != count for outcome, count in expected_totals.items())):
            raise ValueError(f"Simulator counters do not reconcile for {tenant}")
    return {"tenants": totals, "duplicate_rows": 0, "unexplained_losses": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for file in ("simulator", "readings", "receipts"):
        parser.add_argument(file, type=Path)
    args = parser.parse_args()
    try:
        with (open_snapshot(args.simulator) as db,
              args.readings.open(newline="", encoding="utf-8") as raw,
              args.receipts.open(newline="", encoding="utf-8") as ledger):
            report = reconcile(db, csv.reader(raw), csv.reader(ledger))
    except (OSError, ValueError, sqlite3.Error) as error:
        parser.exit(1, f"{error}\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
