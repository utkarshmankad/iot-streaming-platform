#!/usr/bin/env python3
"""Reconcile a drained pilot's simulator journal, raw readings, and receipt ledger."""

import argparse
import csv
import json
import sqlite3
from collections import Counter, defaultdict
from contextlib import closing
from itertools import zip_longest
from pathlib import Path


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
        if pending or generated != sum(expected_totals.values()) or expected_totals != totals[tenant]:
            raise ValueError(f"Simulator counters do not reconcile for {tenant}")
    return {"tenants": totals, "duplicate_rows": 0, "unexplained_losses": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for file in ("simulator", "readings", "receipts"):
        parser.add_argument(file, type=Path)
    args = parser.parse_args()
    try:
        with (closing(sqlite3.connect(args.simulator.resolve().as_uri() + "?mode=ro", uri=True)) as db,
              args.readings.open(newline="", encoding="utf-8") as raw,
              args.receipts.open(newline="", encoding="utf-8") as ledger):
            report = reconcile(db, csv.reader(raw), csv.reader(ledger))
    except (OSError, ValueError, sqlite3.Error) as error:
        parser.exit(1, f"{error}\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
