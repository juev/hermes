"""Supplemental frozen-code control: measured real loopback payloads, synthetic workload.

This is not a model benchmark, TLS/HTTP byte accounting, or a battery measurement.
"""
import asyncio
import importlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

import websockets
from websockets.asyncio.server import serve

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_wire_plugin", ROOT / "__init__.py", submodule_search_locations=[str(ROOT)])
assert spec is not None and spec.loader is not None
package = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = package
spec.loader.exec_module(package)
NativeClient = importlib.import_module(spec.name + ".native").NativeClient
Registry = importlib.import_module(spec.name + ".registry").Registry
relay = importlib.import_module(spec.name + ".socket").relay


class WireTest(unittest.IsolatedAsyncioTestCase):
    async def test_actual_minimal_socket_omits_heavy_payloads_and_keeps_question_and_final(self):
        async def native_peer(ws):
            async def send(value):
                await ws.send(json.dumps(value, ensure_ascii=False))

            async def event(kind, payload, seq, sid="owned"):
                await send({"method": "event", "params": {"type": kind, "session_id": sid, "seq": seq, "payload": payload}})

            await send({"method": "event", "params": {"type": "gateway.ready", "payload": {"session_authority": True, "heartbeat": True}}})
            async for raw in ws:
                call = json.loads(raw)
                method = call.get("method")
                if method == "session.create":
                    result = {"session_id": "owned", "stored_session_id": "stored", "messages": [], "subscription_id": "sub", "execution_generation": 1}
                elif method == "prompt.submit":
                    result = {"status": "queued", "admission_id": "admitted", "ref": {"session_id": "owned"}, "authority_epoch": 1}
                elif method is None:
                    self.assertEqual("submitted", call["result"]["outcome"])
                    await event("message.delta", {"text": "Visible final"}, 112)
                    await event("message.complete", {"status": "complete", "text": "Visible final"}, 113)
                    continue
                else:
                    result = {}
                await send({"id": call["id"], "result": result})
                if method == "prompt.submit":
                    await event("message.start", {}, 1)
                    for seq in range(2, 102):
                        await event("reasoning.delta", {"text": "PRIVATE_REASONING " * 128}, seq)
                    for seq in range(102, 112):
                        await event("tool.complete", {"arguments": "PRIVATE_ARGUMENTS " * 64, "result": "PRIVATE_TOOL_RESULT " * 1024}, seq)
                    await event("message.delta", {"text": "PRIVATE_FOREIGN"}, 1, sid="telegram-foreign")
                    await send({"id": "srq1", "method": "clarify", "params": {"session_id": "owned", "questions": []}})

        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            store = Registry(Path(directory))
            async with serve(native_peer, "127.0.0.1", 0) as upstream:
                source = "ws://127.0.0.1:" + str(upstream.sockets[0].getsockname()[1])

                async def minimal_peer(ws):
                    class Adapter:
                        async def send_json(self, value):
                            await ws.send(json.dumps(value, ensure_ascii=False))
                        async def receive_json(self):
                            return json.loads(await ws.recv())

                    async with websockets.connect(source, proxy=None, compression=None) as native_ws:
                        native = NativeClient(native_ws)
                        try:
                            await native.start()
                            await relay(Adapter(), native, store, "wire-actor")
                        except websockets.exceptions.ConnectionClosed:
                            pass
                        finally:
                            await native.stop()

                async with serve(minimal_peer, "127.0.0.1", 0) as frontend:
                    target = "ws://127.0.0.1:" + str(frontend.sockets[0].getsockname()[1])

                    async def exercise(url):
                        captured = []
                        async with websockets.connect(url, proxy=None, compression=None) as ws:
                            async def receive():
                                raw = await ws.recv()
                                captured.append(raw)
                                return json.loads(raw)
                            await receive()
                            for number, method, params in [(1, "client.capabilities", {"server_requests": True}), (2, "session.create", {}), (3, "prompt.submit", {"session_id": "owned", "text": "Synthetic workload"})]:
                                await ws.send(json.dumps({"id": str(number), "method": method, "params": params}))
                                while (await receive()).get("id") != str(number):
                                    pass
                            async with asyncio.timeout(10):
                                while True:
                                    frame = await receive()
                                    if frame.get("method") == "clarify":
                                        await ws.send(json.dumps({"id": frame["id"], "result": {"outcome": "submitted"}}))
                                    if (frame.get("params") or {}).get("type") == "message.complete":
                                        break
                        return captured

                    full = await exercise(source)
                    minimal = await exercise(target)
        full_text, minimal_text = "\n".join(full), "\n".join(minimal)
        self.assertIn("PRIVATE_TOOL_RESULT", full_text)
        self.assertNotIn("PRIVATE", minimal_text)
        self.assertIn("Visible final", minimal_text)
        self.assertEqual(1, sum(json.loads(row).get("method") == "clarify" for row in minimal))
        measured = {"kind": "synthetic_loopback_websocket_application_payload", "full_frames": len(full), "minimal_frames": len(minimal),
                    "full_bytes": sum(len(row.encode("utf-8")) for row in full), "minimal_bytes": sum(len(row.encode("utf-8")) for row in minimal)}
        self.assertLess(measured["minimal_bytes"], measured["full_bytes"] / 5)
        self.assertLess(measured["minimal_frames"], measured["full_frames"] / 5)
        print("WIRE_MEASUREMENT " + json.dumps(measured, sort_keys=True))


if __name__ == "__main__":
    unittest.main()
