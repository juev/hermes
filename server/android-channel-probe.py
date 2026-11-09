"""Opt-in native transport feasibility probe; not the connection plugin.

Uses the installed gateway client only as a test driver. Never starts/restarts a
runtime, never changes its model/toolsets/config, and logs no credentials, tools
or reasoning. Creates one explicitly titled integration-test session.
"""
import asyncio
import json
import uuid
from pathlib import Path

from hermes_cli.gateway_client import connect_gateway as _native_gateway
from hermes_cli.gateway_runtime import discover_gateway_endpoint
from hermes_constants import get_hermes_home
from contextlib import asynccontextmanager
import importlib
import importlib.util
import sys
import tempfile

_BRIDGE_ROOT = None
_TRAFFIC = []


@asynccontextmanager
async def connect_gateway(*, endpoint):
    if _BRIDGE_ROOT is None:
        async with _native_gateway(endpoint=endpoint) as client:
            yield client
        return
    # Operator-only test bootstrap, never imported by the installed plugin.
    from hermes_cli.gateway_client import _session_ticket, gateway_ws_target
    import websockets
    root = Path(__file__).parent / "hermes-android"
    name = "hermes_android_live_probe"
    if name not in sys.modules:
        spec = importlib.util.spec_from_file_location(name, root / "__init__.py", submodule_search_locations=[str(root)])
        assert spec is not None and spec.loader is not None
        package = importlib.util.module_from_spec(spec)
        sys.modules[name] = package
        spec.loader.exec_module(package)
    NativeClient = importlib.import_module(name + ".native").NativeClient
    Bridge = importlib.import_module(name + ".bridge").Bridge
    Registry = importlib.import_module(name + ".registry").Registry
    ticket = await asyncio.to_thread(_session_ticket, get_hermes_home(), endpoint)
    url, protocols = gateway_ws_target(endpoint, ticket)
    async with websockets.connect(url, subprotocols=protocols, proxy=None, max_size=8 * 1024 * 1024) as ws:
        native = NativeClient(ws)
        try:
            await native.start()
            bridge = Bridge(native, Registry(_BRIDGE_ROOT), "operator-live-bridge-probe")

            class Queue:
                async def get(self):
                    while True:
                        frame = await native.events.get()
                        if isinstance(frame, Exception):
                            return frame
                        projected = bridge.forward(frame)
                        if projected is not None:
                            return projected

            class Socket:
                async def send(self, payload):
                    await bridge.respond(json.loads(payload))

            class Driver:
                events = Queue()
                websocket = Socket()

                async def rpc(self, method, **params):
                    return await bridge.request(method, params)

            yield Driver()
        finally:
            await native.stop()
            _TRAFFIC.append({"native_inbound_bytes": native.native_bytes,
                             "minimal_event_bytes_before_channel_check": native.projected_bytes})


async def run():
    discovery = discover_gateway_endpoint(get_hermes_home())
    if discovery.state != "ready" or discovery.endpoint is None:
        raise RuntimeError(f"Existing gateway is not ready: {discovery.state}")
    endpoint = discovery.endpoint
    receipt = {"kind": "live_plugin_bridge" if _BRIDGE_ROOT else "live_native_feasibility", "created": False, "reconnected": False}
    session = None
    try:
        async with connect_gateway(endpoint=endpoint) as client:
            if _BRIDGE_ROOT is None:
                description = await client.rpc("runtime.describe")
                receipt["session_create"] = description.get("session_create")
            await client.rpc("client.capabilities", server_requests=True)
            snapshot = await client.rpc(
                "session.create", source="tool", title="Android plugin #155 probe " + uuid.uuid4().hex[:12]
            )
            session = snapshot["session_id"]
            receipt.update(created=True, session_id=session)
            receipt["submit"] = await client.rpc(
                "prompt.submit", session_id=session, clarify_batch=True, submission_id=uuid.uuid4().hex,
                text=("This is an integration probe for Android plugin #155. "
                      "Do not read files or run commands. Call the clarify tool once "
                      "with one question 'Continue plugin transport probe?' and choices "
                      "['Continue', 'Cancel']. After the answer reply exactly "
                      "ANDROID_PLUGIN_PROBE_OK. Do not use other tools.")
            )
            async with asyncio.timeout(180):
                while True:
                    frame = await client.events.get()
                    if isinstance(frame, Exception):
                        raise frame
                    if frame.get("method") == "clarify":
                        receipt["request_received"] = True
                        receipt["request_id"] = frame["id"]
                        break
                    params = frame.get("params", {})
                    if params.get("type") == "message.complete":
                        receipt["terminal_before_question"] = params.get("payload", {}).get("status")
                        raise AssertionError("Turn completed without the required interactive request")
        async with connect_gateway(endpoint=endpoint) as client:
            await client.rpc("client.capabilities", server_requests=True)
            snapshot = await client.rpc("session.resume", session_id=session, omit_messages=True)
            requests = snapshot.get("open_requests") or []
            question = next((q for q in requests if q.get("id") == receipt.get("request_id")), None)
            if question is None:
                raise AssertionError("Resume did not restore the unanswered native request")
            receipt["reconnected"] = True
            questions = question["params"]["questions"]
            answers = {q["qid"]: "Continue" for q in questions}
            await client.websocket.send(json.dumps({
                "jsonrpc": "2.0", "id": question["id"],
                "result": {"answers": answers, "outcome": "submitted"},
            }))
            async with asyncio.timeout(180):
                while True:
                    frame = await client.events.get()
                    if isinstance(frame, Exception):
                        raise frame
                    params = frame.get("params", {})
                    if params.get("session_id") == session and params.get("type") == "message.complete":
                        payload = params.get("payload", {})
                        receipt["terminal_status"] = payload.get("status")
                        receipt["answer_matches"] = payload.get("text", "").strip() == "ANDROID_PLUGIN_PROBE_OK"
                        if not receipt["answer_matches"]:
                            raise AssertionError("Final native reply did not match the probe contract")
                        break
            await client.rpc("session.detach", session_id=session, subscription_id=snapshot["subscription_id"], end_reason="probe_complete")
            receipt["viewer_closed"] = True
    except Exception as exc:
        receipt["failure"] = {"type": type(exc).__name__, "message": str(exc)[:300], "native_code": getattr(exc, "code", None)}
        if session:
            try:
                async with connect_gateway(endpoint=endpoint) as client:
                    current = await client.rpc("session.resume", session_id=session, omit_messages=True)
                    if current.get("running"):
                        await client.rpc("session.interrupt", session_id=session, execution_generation=current["execution_generation"])
                    await client.rpc("session.detach", session_id=session, subscription_id=current["subscription_id"], end_reason="probe_failed")
                receipt["failed_probe_interrupted"] = True
            except Exception as cleanup:
                receipt["cleanup_failure"] = type(cleanup).__name__
        raise
    finally:
        if _BRIDGE_ROOT is not None:
            receipt["traffic"] = _TRAFFIC
        target = Path(__file__).parents[1] / ("build/channel-private/android-channel-native-probe-" + uuid.uuid4().hex + ".json")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(receipt, ensure_ascii=False))


if __name__ == "__main__":
    if "--bridge" in sys.argv:
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[1]) as directory:
            _BRIDGE_ROOT = Path(directory)
            asyncio.run(run())
    else:
        asyncio.run(run())
