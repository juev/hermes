"""Actual ASGI bearer forwarding and loopback ticketed WebSocket, synthetic peer."""
import importlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest

from fastapi import FastAPI, Request, HTTPException
from websockets.asyncio.server import serve

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_upstream_plugin", ROOT / "__init__.py", submodule_search_locations=[str(ROOT)])
assert spec is not None and spec.loader is not None
package = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = package
spec.loader.exec_module(package)
upstream = importlib.import_module("android_upstream_plugin.upstream")


class UpstreamTest(unittest.IsolatedAsyncioTestCase):
    async def test_legacy_native_gateway_is_rejected_before_exposing_channel_transport(self):
        async def peer(ws):
            await ws.send(json.dumps({"method": "event", "params": {"type": "gateway.ready", "payload": {"session_authority": False}}}))
            await ws.wait_closed()
        accepted = False
        async with serve(peer, "127.0.0.1", 0) as server:
            port = tuple(server.sockets)[0].getsockname()[1]
            try:
                async with upstream.connection({"native_ticket": "synthetic-ticket", "server": ("127.0.0.1", port)}):
                    accepted = True
            except ConnectionError:
                pass
        self.assertFalse(accepted, "The v1 plugin requires canonical ownership; an older full-data transport must not be a silent fallback")

    async def test_verified_bearer_mints_native_ticket_for_loopback_connection(self):
        app = FastAPI()
        received = []

        @app.post("/api/auth/ws-ticket")
        async def ticket(request: Request):
            if request.headers.get("authorization") != "Bearer fixture-token":
                raise HTTPException(status_code=401)
            received.append(request.headers["authorization"])
            return {"ticket": "synthetic-ticket", "ttl_seconds": 30}

        async def peer(ws):
            self.assertEqual("/api/ws?ticket=synthetic-ticket", ws.request.path)
            await ws.send(json.dumps({"method": "event", "params": {"type": "gateway.ready", "payload": {"session_authority": True}}}))
            call = json.loads(await ws.recv())
            await ws.send(json.dumps({"id": call["id"], "result": {"pong": True}}))
            await ws.wait_closed()

        async with serve(peer, "127.0.0.1", 0) as server:
            port = tuple(server.sockets)[0].getsockname()[1]
            request = Request({"type": "http", "method": "POST", "path": "/plugin/ws-ticket", "query_string": b"", "scheme": "http", "server": ("127.0.0.1", port), "headers": [(b"authorization", b"Bearer fixture-token")], "app": app})
            native_ticket = await upstream.native_http(request, "POST", "api/auth/ws-ticket")
            self.assertEqual("synthetic-ticket", native_ticket.get("ticket"), "The internal hop must pass the verified bearer to the native authentication route")
            self.assertEqual(["Bearer fixture-token"], received)
            async with upstream.connection({"native_ticket": native_ticket["ticket"], "server": ("untrusted-host.invalid", port)}) as native:
                self.assertTrue(native.canonical)
                self.assertEqual({"pong": True}, await native.rpc("ping"))


if __name__ == "__main__":
    unittest.main()
