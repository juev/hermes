"""Server-owned channel membership; never infer ownership from source labels."""
import os
from pathlib import Path
import sqlite3
from contextlib import closing


class Registry:
    def __init__(self, root):
        root = Path(root)
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = root / "channels.sqlite3"
        fd = os.open(self.path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        os.close(fd)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS sessions (session TEXT PRIMARY KEY, owner TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS aliases (session TEXT PRIMARY KEY, logical TEXT NOT NULL)")

    def claim(self, owner, session):
        if not isinstance(owner, str) or not owner or not isinstance(session, str) or not session:
            raise ValueError("Missing channel identity")
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT owner FROM sessions WHERE session=? UNION ALL SELECT s.owner FROM aliases a JOIN sessions s ON s.session=a.logical WHERE a.session=?", (session, session)).fetchone()
            if existing is not None and existing[0] != owner:
                raise PermissionError("Session belongs to another channel")
            if existing is None:
                db.execute("INSERT INTO sessions (session, owner) VALUES (?, ?)", (session, owner))

    def owns(self, owner, session):
        return bool(owner) and self.owner(session) == owner

    def owner(self, session):
        with closing(sqlite3.connect(self.path)) as db, db:
            row = db.execute("SELECT owner FROM sessions WHERE session=? UNION ALL SELECT s.owner FROM aliases a JOIN sessions s ON s.session=a.logical WHERE a.session=?", (session, session)).fetchone()
            return row[0] if row else None

    def logical(self, session):
        with closing(sqlite3.connect(self.path)) as db, db:
            row = db.execute("SELECT session FROM sessions WHERE session=? UNION ALL SELECT logical FROM aliases WHERE session=?", (session, session)).fetchone()
            return row[0] if row else None

    def alias(self, previous, current):
        if not isinstance(previous, str) or not previous or not isinstance(current, str) or not current:
            return
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute("SELECT session FROM sessions WHERE session=? UNION ALL SELECT logical FROM aliases WHERE session=?", (previous, previous)).fetchone()
            if old is None or current == old[0]:
                return
            existing = db.execute("SELECT session FROM sessions WHERE session=? UNION ALL SELECT logical FROM aliases WHERE session=?", (current, current)).fetchone()
            if existing is not None and existing[0] != old[0]:
                raise PermissionError("A compaction alias cannot replace another conversation")
            db.execute("INSERT OR IGNORE INTO aliases (session, logical) VALUES (?, ?)", (current, old[0]))

    def sessions(self, owner):
        with closing(sqlite3.connect(self.path)) as db, db:
            return [row[0] for row in db.execute("SELECT session FROM sessions WHERE owner=? ORDER BY rowid", (owner,))]
