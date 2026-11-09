"""Private per-principal Web Push, independent of a connected phone socket."""
from contextlib import closing
import hashlib
import logging
import sqlite3

from . import webpush

log = logging.getLogger(__name__)
CHANNEL = "android-minimal-v1"
SUBJECT = "mailto:hermes-android@localhost"


def identity(owner):
    return hashlib.sha256(owner.encode("utf-8")).hexdigest()


class PushService:
    def __init__(self, registry, sender=None):
        self.registry = registry
        self.sender = sender or webpush.send
        with closing(sqlite3.connect(registry.path)) as db, db:
            db.execute("CREATE TABLE IF NOT EXISTS push_keys (owner TEXT PRIMARY KEY, pem BLOB NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS push_subscriptions (endpoint TEXT PRIMARY KEY, owner TEXT NOT NULL, p256dh TEXT NOT NULL, auth TEXT NOT NULL, name TEXT NOT NULL, device TEXT NOT NULL)")

    def key(self, owner, create=False):
        with closing(sqlite3.connect(self.registry.path)) as db, db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT pem FROM push_keys WHERE owner=?", (owner,)).fetchone()
            if row:
                return webpush.Vapid.from_pem(row[0])
            if not create:
                return None
            key = webpush.Vapid.generate()
            db.execute("INSERT INTO push_keys (owner,pem) VALUES (?,?)", (owner, key.pem()))
            return key

    def subscribe(self, owner, endpoint, p256dh, auth, name="", device=""):
        if not endpoint.startswith("https://"):
            raise ValueError("A push endpoint must use HTTPS")
        self.key(owner, create=True)
        with closing(sqlite3.connect(self.registry.path)) as db, db:
            db.execute("BEGIN IMMEDIATE")
            if device:
                db.execute("DELETE FROM push_subscriptions WHERE owner=? AND device=?", (owner, device))
            # A newly registered endpoint belongs to one sign-in, not both the
            # previous and current accounts on this phone.
            db.execute("INSERT INTO push_subscriptions VALUES (?,?,?,?,?,?) ON CONFLICT(endpoint) DO UPDATE SET owner=excluded.owner,p256dh=excluded.p256dh,auth=excluded.auth,name=excluded.name,device=excluded.device",
                       (endpoint, owner, p256dh, auth, name, device))
            db.execute("DELETE FROM push_subscriptions WHERE owner=? AND rowid NOT IN (SELECT rowid FROM push_subscriptions WHERE owner=? ORDER BY rowid DESC LIMIT 20)", (owner, owner))

    def unsubscribe(self, owner, endpoint):
        with closing(sqlite3.connect(self.registry.path)) as db, db:
            return db.execute("DELETE FROM push_subscriptions WHERE owner=? AND endpoint=?", (owner, endpoint)).rowcount > 0

    def _send(self, owner, message):
        key = self.key(owner)
        if key is None:
            return 0
        with closing(sqlite3.connect(self.registry.path)) as db, db:
            subscriptions = db.execute("SELECT endpoint,p256dh,auth FROM push_subscriptions WHERE owner=?", (owner,)).fetchall()
        sent = 0
        for endpoint, p256dh, auth in subscriptions:
            try:
                self.sender(endpoint, p256dh, auth, message, key, SUBJECT)
                sent += 1
            except webpush.PushGone:
                self.unsubscribe(owner, endpoint)
            except Exception:
                log.warning("Android channel push delivery failed")  # No endpoint, keys or message in logs.
        return sent

    def deliver(self, session_id, response):
        owner = self.registry.owner(session_id)
        logical = self.registry.logical(session_id)
        if owner is None or logical is None or not isinstance(response, str):
            return 0
        return self._send(owner, {"type": "turn", "session_id": logical, "platform": "android", "job": None,
                                  "channel": CHANNEL, "channel_owner": identity(owner), "preview": " ".join(response.split())[:600]})
