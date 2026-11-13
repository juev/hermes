"""Durable per-chat outbox and chat registry for the Android channel.

The phone is not always connected: a turn can finish while the app is in the
background or on another network. Every user-visible frame is therefore written
before it is pushed, and replayed on the next ``hello`` from its chat. This is
the only server state the transport keeps — sessions, history, ownership and
authorization stay in the gateway.
"""

import json
import os
import sqlite3
import time
from contextlib import closing
from pathlib import Path

KEEP_PER_CHAT = 500


def default_root() -> Path:
    try:
        from plugins.plugin_storage import plugin_data_dir

        return plugin_data_dir("hermes-android-transport")
    except Exception:  # pragma: no cover - only outside a plugin-aware process
        root = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))) / "plugin-data" / "hermes-android-transport"
        root.mkdir(parents=True, exist_ok=True)
        return root


class ChannelStore:
    """``channels.sqlite3``: chat names plus an append-only, per-chat outbox."""

    def __init__(self, root=None, *, keep: int = KEEP_PER_CHAT):
        root = Path(root or default_root())
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = root / "channels.sqlite3"
        self.keep = keep
        fd = os.open(self.path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        os.close(fd)
        with closing(self._connect()) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS chats (chat_id TEXT PRIMARY KEY, name TEXT, updated REAL NOT NULL)")
            db.execute(
                "CREATE TABLE IF NOT EXISTS outbox ("
                "seq INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL, "
                "payload TEXT NOT NULL, created REAL NOT NULL)"
            )
            db.execute("CREATE INDEX IF NOT EXISTS outbox_chat ON outbox (chat_id, seq)")

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path, timeout=10)

    def touch_chat(self, chat_id: str, name: str | None = None) -> None:
        with closing(self._connect()) as db, db:
            db.execute(
                "INSERT INTO chats (chat_id, name, updated) VALUES (?, ?, ?) "
                "ON CONFLICT(chat_id) DO UPDATE SET name=COALESCE(excluded.name, chats.name), updated=excluded.updated",
                (chat_id, name, time.time()),
            )

    def name(self, chat_id: str) -> str | None:
        with closing(self._connect()) as db, db:
            row = db.execute("SELECT name FROM chats WHERE chat_id=?", (chat_id,)).fetchone()
            return row[0] if row else None

    def chats(self) -> list[dict]:
        with closing(self._connect()) as db, db:
            rows = db.execute(
                "SELECT c.chat_id, c.name, COALESCE(MAX(o.seq), 0) FROM chats c "
                "LEFT JOIN outbox o ON o.chat_id = c.chat_id GROUP BY c.chat_id ORDER BY c.updated DESC"
            ).fetchall()
            return [{"chat_id": row[0], "name": row[1], "last_seq": row[2]} for row in rows]

    def last_seq(self, chat_id: str) -> int:
        with closing(self._connect()) as db, db:
            row = db.execute("SELECT COALESCE(MAX(seq), 0) FROM outbox WHERE chat_id=?", (chat_id,)).fetchone()
            return int(row[0] or 0)

    def append(self, chat_id: str, payload: dict) -> int:
        """Store one user-visible frame and return its monotonic sequence number."""
        body = json.dumps(payload, ensure_ascii=False)
        with closing(self._connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            cursor = db.execute(
                "INSERT INTO outbox (chat_id, payload, created) VALUES (?, ?, ?)", (chat_id, body, time.time())
            )
            seq = int(cursor.lastrowid or 0)
            db.execute(
                "DELETE FROM outbox WHERE chat_id=? AND seq <= ("
                "SELECT seq FROM outbox WHERE chat_id=? ORDER BY seq DESC LIMIT 1 OFFSET ?)",
                (chat_id, chat_id, self.keep),
            )
            return seq

    def replay(self, chat_id: str, after_seq: int = 0, limit: int = 200) -> list[dict]:
        with closing(self._connect()) as db, db:
            rows = db.execute(
                "SELECT seq, payload FROM outbox WHERE chat_id=? AND seq>? ORDER BY seq LIMIT ?",
                (chat_id, int(after_seq), int(limit)),
            ).fetchall()
        frames = []
        for seq, payload in rows:
            try:
                frame = json.loads(payload)
            except json.JSONDecodeError:  # pragma: no cover - only a corrupted local row
                continue
            frame["seq"] = seq
            frames.append(frame)
        return frames
