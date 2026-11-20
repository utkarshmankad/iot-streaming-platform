"""Stream an SQLite Online Backup API snapshot from a live queue volume."""

import shutil
import sqlite3
import sys
import tempfile
from contextlib import closing
from pathlib import Path

source = Path(sys.argv[1])
with tempfile.TemporaryDirectory() as staging:
    snapshot = Path(staging) / "snapshot.sqlite"
    with closing(sqlite3.connect(f"{source.as_uri()}?mode=ro", uri=True, timeout=5)) as live:
        with closing(sqlite3.connect(snapshot)) as destination:
            live.backup(destination, pages=256)
            if destination.execute("PRAGMA quick_check").fetchone() != ("ok",):
                raise SystemExit("SQLite snapshot integrity check failed")
    with snapshot.open("rb") as ready:
        shutil.copyfileobj(ready, sys.stdout.buffer)
