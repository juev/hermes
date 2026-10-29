"""Routes of hermes-push under /api/plugins/hermes-push, behind the same sign-in as the dashboard.

The dashboard loads this file on its own, so the plugin's modules are loaded from their path.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

_ROOT = Path(__file__).resolve().parent.parent
_PACKAGE = "hermes_push_api"


def _load():
    if _PACKAGE in sys.modules:
        return sys.modules[_PACKAGE]
    spec = importlib.util.spec_from_file_location(_PACKAGE, _ROOT / "__init__.py", submodule_search_locations=[str(_ROOT)])
    module = importlib.util.module_from_spec(spec)
    sys.modules[_PACKAGE] = module
    spec.loader.exec_module(module)
    return module


plugin = _load()
store = plugin.store
router = APIRouter()


class Subscription(BaseModel):
    endpoint: str
    p256dh: str
    auth: str
    name: str = ""
    # A stable id of the phone: its new subscription replaces the ones it no longer reads.
    device: str = ""


class Endpoint(BaseModel):
    endpoint: str


@router.get("/key")
async def key():
    """The VAPID public key the phone registers with its distributor."""
    return {"key": store.vapid(create=True).public_key}


@router.post("/subscriptions")
async def subscribe(body: Subscription):
    if not body.endpoint.startswith("https://"):
        raise HTTPException(status_code=400, detail="endpoint must be https")
    store.subscribe(body.endpoint, body.p256dh, body.auth, body.name, body.device)
    return {"ok": True}


@router.delete("/subscriptions")
async def unsubscribe(body: Endpoint):
    return {"ok": True, "removed": store.unsubscribe(body.endpoint)}


@router.post("/test")
async def test():
    """Sends a test push to every subscription, so the phone can show that the chain works."""
    sent = plugin.deliver({"type": "test", "preview": "Hermes push works"})
    return {"ok": True, "sent": sent}
