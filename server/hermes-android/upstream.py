"""Only public native routes; no runtime internals, monkeypatching or new model."""
from contextlib import asynccontextmanager
from urllib.parse import urlencode

from fastapi import HTTPException
import httpx
import websockets

from .native import NativeClient


async def native_http(request, method, path, **kwargs):
    # Invoke the authenticated native ASGI router, not a Host-header-selected URL.
    # The app validates the original bearer again; no operator credentials are used.
    transport = httpx.ASGITransport(app=request.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://127.0.0.1", timeout=120) as client:
        response = await client.request(method, "/" + path.lstrip("/"),
                                        headers={"Authorization": request.headers.get("authorization", "")}, **kwargs)
    if response.status_code >= 400:
        raise HTTPException(status_code=response.status_code, detail="Native request refused")
    return response.json()


@asynccontextmanager
async def connection(entry):
    # Hermes' dashboard's plaintext loopback listener; never trust the HTTP Host
    # or forwarded scheme to select a destination. TLS terminates at the proxy.
    server = entry.get("server")
    if not isinstance(server, (tuple, list)) or len(server) != 2 or not isinstance(server[1], int) or not 1 <= server[1] <= 65535:
        raise ConnectionError("The dashboard must expose a local listener")
    url = "ws://127.0.0.1:" + str(server[1]) + "/api/ws?" + urlencode({"ticket": entry["native_ticket"]})
    async with websockets.connect(url, proxy=None, max_size=8 * 1024 * 1024, open_timeout=15) as ws:
        native = NativeClient(ws)
        try:
            await native.start()
            if not native.canonical:
                raise ConnectionError("Canonical session authority is required for Android channel v1")
            yield native
        finally:
            await native.stop()
