"""Real loopback WebSocket with an explicitly synthetic native peer."""
import asyncio
import importlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest

import websockets
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_native_plugin", ROOT / "__init__.py", submodule_search_locations=[str(ROOT)])
assert spec is not None and spec.loader is not None
package = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = package
spec.loader.exec_module(package)
native = importlib.import_module("android_native_plugin.native")


class NativeTest(unittest.IsolatedAsyncioTestCase):
    async def test_start_waits_for_the_native_handshake_before_exposing_transport(self):
        release = asyncio.Event()

        async def peer(ws):
            await release.wait()
            await ws.send(json.dumps({"method": "event", "params": {"type": "gateway.ready", "payload": {"session_authority": True}}}))
            await ws.wait_closed()

        async with serve(peer, "127.0.0.1", 0) as server:
            port = tuple(server.sockets)[0].getsockname()[1]
            async with websockets.connect(f"ws://127.0.0.1:{port}", proxy=None) as ws:
                client = native.NativeClient(ws)
                starting = asyncio.create_task(client.start())
                try:
                    await asyncio.sleep(0)
                    self.assertFalse(starting.done(), "Native readiness must not precede gateway.ready")
                    release.set()
                    await asyncio.wait_for(starting, timeout=5)
                    self.assertTrue(client.canonical)
                finally:
                    release.set()
                    await starting
                    await client.stop()

    async def test_native_rpc_and_question_flow_does_not_queue_reasoning_or_tool_results(self):
        answered = asyncio.Event()

        async def peer(ws):
            try:
                await ws.send(json.dumps({"method": "event", "params": {"type": "gateway.ready", "payload": {"heartbeat": True}}}))
                call = json.loads(await ws.recv())
                await ws.send(json.dumps({"jsonrpc": "2.0", "id": call["id"], "result": {"status": "streaming"}}))
                for kind in ("reasoning.delta", "tool.complete"):
                    await ws.send(json.dumps({"method": "event", "params": {"type": kind, "session_id": "owned", "payload": {"text": "PRIVATE", "result": "PRIVATE"}}}))
                await ws.send(json.dumps({"method": "event", "params": {"type": "message.delta", "session_id": "owned", "payload": {"text": "Visible"}}}))
                await ws.send(json.dumps({"jsonrpc": "2.0", "id": "srq1", "method": "clarify", "params": {"session_id": "owned", "questions": []}}))
                response = json.loads(await ws.recv())
                if response == {"jsonrpc": "2.0", "id": "srq1", "result": {"outcome": "cancelled"}}:
                    answered.set()
            except ConnectionClosed:
                pass

        async with serve(peer, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            async with websockets.connect(f"ws://127.0.0.1:{port}", proxy=None) as ws:
                client = native.NativeClient(ws)
                await client.start()
                try:
                    result = await client.rpc("prompt.submit", session_id="owned", text="Fixture")
                    self.assertEqual({"status": "streaming"}, result)
                    async with asyncio.timeout(5):
                        ready = await client.events.get()
                        self.assertEqual("gateway.ready", ready["params"]["type"])
                        visible = await client.events.get()
                        self.assertEqual("message.delta", visible["params"]["type"], "Private work events must be dropped before the bounded queue")
                        question = await client.events.get()
                        self.assertEqual("clarify", question["method"])
                        await client.respond({"jsonrpc": "2.0", "id": "srq1", "result": {"outcome": "cancelled"}})
                        await answered.wait()
                finally:
                    await client.stop()


if __name__ == "__main__":
    unittest.main()
