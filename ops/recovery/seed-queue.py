import os
import shutil
import sqlite3
import sys
from contextlib import closing
from pathlib import Path

directory = Path("/restored")
path = directory / sys.argv[1]
with path.open("xb") as target:
    shutil.copyfileobj(sys.stdin.buffer, target)
if sys.argv[2] == "gateway":
    with closing(sqlite3.connect(path)) as db, db:
        db.execute("DELETE FROM command_acks")
for item in directory.iterdir():
    item.chmod(0o600)
    os.chown(item, 10001, 10001)
os.chown(directory, 10001, 10001)
