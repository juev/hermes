"""Subscriptions of the phones and the VAPID key of the server, shared by the dashboard and the gateway.

Both processes run in the same HERMES_HOME, so the data lives in one JSON file under
``plugin-data/hermes-push``; every write replaces the file atomically.
"""

from __future__ import annotations

import json
import os
import tempfile
import threading
from pathlib import Path

from .webpush import Vapid

NAME = "hermes-push"
MAX_SUBSCRIPTIONS = 20
_lock = threading.Lock()


def data_dir(root: Path | None = None) -> Path:
    if root is not None:
        root.mkdir(parents=True, exist_ok=True)
        return root
    from plugins.plugin_storage import plugin_data_dir
    return plugin_data_dir(NAME)


def _atomic_write(path: Path, data: bytes, mode: int = 0o600) -> None:
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=path.name + ".")
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, "wb") as out:
            out.write(data)
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


def vapid(root: Path | None = None, create: bool = False) -> Vapid | None:
    """The server key; created on the first request of a phone, never by the gateway hook."""
    path = data_dir(root) / "vapid.pem"
    with _lock:
        if path.exists():
            return Vapid.from_pem(path.read_bytes())
        if not create:
            return None
        key = Vapid.generate()
        _atomic_write(path, key.pem())
        return key


def subscriptions(root: Path | None = None) -> list[dict]:
    path = data_dir(root) / "subscriptions.json"
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, ValueError):
        return []


def _save(items: list[dict], root: Path | None) -> None:
    _atomic_write(data_dir(root) / "subscriptions.json", json.dumps(items, indent=1).encode())


def subscribe(endpoint: str, p256dh: str, auth: str, name: str = "", root: Path | None = None) -> None:
    """Adds the phone, or refreshes its keys; the oldest subscriptions go beyond the limit."""
    with _lock:
        items = [s for s in subscriptions(root) if s.get("endpoint") != endpoint]
        items.append({"endpoint": endpoint, "p256dh": p256dh, "auth": auth, "name": name})
        _save(items[-MAX_SUBSCRIPTIONS:], root)


def unsubscribe(endpoint: str, root: Path | None = None) -> bool:
    with _lock:
        items = subscriptions(root)
        kept = [s for s in items if s.get("endpoint") != endpoint]
        if len(kept) != len(items):
            _save(kept, root)
        return len(kept) != len(items)
