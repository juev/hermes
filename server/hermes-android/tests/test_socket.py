"""ASGI WebSocket integration with an explicitly synthetic native peer."""
import asyncio
from contextlib import asynccontextmanager
import importlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest

from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_socket_plugin", ROOT / "__init__.py", submodule_search_locations=[str(ROOT)])
assert spec is not None and spec.loader is not None
package = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = package
spec.loader.exec_module(package)
api = importlib.import_module("android_socket_plugin.api")
registry = importlib.import_module("android_socket_plugin.registry")


class Peer:
    canonical = False

    def __init__(self):
        self.events = asyncio.Queue()
        self.calls = []
        self.responses = []

    async def rpc(self, method, **params):
        self.calls.append((method, params))
        if method == "session.create":
            return {"session_id": "live-owned", "stored_session_id": "stored-owned", "messages": [], "info": {}}
        if method == "prompt.submit":
            for kind, sid, payload in [
                ("message.delta", "telegram-foreign", {"text": "PRIVATE_FOREIGN"}),
                ("reasoning.delta", "live-owned", {"text": "PRIVATE_REASONING"}),
                ("tool.complete", "live-owned", {"result": "PRIVATE_RESULT"}),
                ("message.delta", "live-owned", {"text": "Visible"}),
            ]:
                await self.events.put({"method": "event", "params": {"type": kind, "session_id": sid, "payload": payload}})
            await self.events.put({"jsonrpc": "2.0", "id": "srq1", "method": "clarify", "params": {"session_id": "live-owned", "questions": []}})
        return {"status": "streaming"}

    async def respond(self, frame):
        self.responses.append(frame)
        await self.events.put({"method": "event", "params": {"type": "message.complete", "session_id": "live-owned", "payload": {"status": "complete", "text": "Visible final"}}})


class SocketTest(unittest.TestCase):
    def test_ticketed_channel_delivers_only_owned_readable_frames_and_required_question(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            peer = Peer()
            entries = []

            async def native_http(request, method, path, **kwargs):
                self.assertEqual(("POST", "api/auth/ws-ticket"), (method, path))
                return {"ticket": "synthetic-native-ticket", "ttl_seconds": 30}

            @asynccontextmanager
            async def connect(entry):
                entries.append(entry)
                await peer.events.put({"method": "event", "params": {"type": "gateway.ready", "payload": {"heartbeat": True}}})
                yield peer

            app = FastAPI()

            @app.middleware("http")
            async def identity(request, call_next):
                if request.headers.get("authorization") == "Bearer fixture-alice":
                    request.state.session = SimpleNamespace(user_id="alice", provider="fixture", issuer="")
                return await call_next(request)

            app.include_router(api.make_router(registry.Registry(Path(directory)), native_http, connect), prefix="/plugin")
            with TestClient(app) as client:
                self.assertEqual(401, client.post("/plugin/ws-ticket").status_code)
                ticket = client.post("/plugin/ws-ticket", headers={"Authorization": "Bearer fixture-alice"}).json()["ticket"]
                with client.websocket_connect("/plugin/ws?ticket=" + ticket) as ws:
                    try:
                        ready = ws.receive_json()
                    except WebSocketDisconnect as exc:
                        ready = {"closed": exc.code}
                    self.assertEqual("event", ready.get("method"), "An authenticated plugin upgrade must establish the minimal transport")
                    ws.send_json({"id": "1", "method": "session.create", "params": {}})
                    created = ws.receive_json()
                    self.assertEqual("stored-owned", created["result"]["session_key"])
                    before = len(peer.calls)
                    ws.send_json({"id": "2", "method": "session.resume", "params": {"session_id": "telegram-foreign"}})
                    self.assertIn("error", ws.receive_json())
                    self.assertEqual(before, len(peer.calls))
                    ws.send_json({"id": "3", "method": "prompt.submit", "params": {"session_id": "live-owned", "text": "Fixture"}})
                    frames = []
                    while True:
                        frame = ws.receive_json()
                        frames.append(frame)
                        if frame.get("method") == "clarify":
                            ws.send_json({"jsonrpc": "2.0", "id": frame["id"], "result": {"outcome": "cancelled"}})
                        if (frame.get("params") or {}).get("type") == "message.complete":
                            break
                    wire = json.dumps(frames)
                    self.assertNotIn("PRIVATE", wire)
                    self.assertIn("Visible final", wire)
                    self.assertEqual(1, len(peer.responses))
                self.assertEqual(json.dumps(["fixture", "", "alice"], separators=(",", ":")), entries[0]["owner"])
                with self.assertRaises(WebSocketDisconnect):
                    with client.websocket_connect("/plugin/ws?ticket=" + ticket) as ws:
                        ws.receive_json()


if __name__ == "__main__":
    unittest.main()
