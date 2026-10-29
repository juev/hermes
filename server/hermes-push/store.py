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


def _same_phone(item: dict, endpoint: str, name: str, device: str) -> bool:
    """Whether [item] is an older subscription of the phone that subscribes with [endpoint].

    A phone that lost its endpoint (a reinstall, cleared data) comes back with a new one, and ntfy
    keeps taking pushes to the old one, so the old one is known by the id of the device instead.
    Subscriptions made before the app sent that id are known by the name of the phone.
    """
    if item.get("endpoint") == endpoint:
        return True
    if not device:
        return False
    if item.get("device"):
        return item["device"] == device
    return bool(name) and item.get("name") == name


def subscribe(endpoint: str, p256dh: str, auth: str, name: str = "", device: str = "", root: Path | None = None) -> None:
    """Adds the phone in place of its older subscriptions; the oldest subscriptions go beyond the limit."""
    with _lock:
        items = [s for s in subscriptions(root) if not _same_phone(s, endpoint, name, device)]
        items.append({"endpoint": endpoint, "p256dh": p256dh, "auth": auth, "name": name, "device": device})
        _save(items[-MAX_SUBSCRIPTIONS:], root)


def unsubscribe(endpoint: str, root: Path | None = None) -> bool:
    with _lock:
        items = subscriptions(root)
        kept = [s for s in items if s.get("endpoint") != endpoint]
        if len(kept) != len(items):
            _save(kept, root)
        return len(kept) != len(items)
