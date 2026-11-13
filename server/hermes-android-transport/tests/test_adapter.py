"""Transport tests against the real Hermes platform seam.

The adapter is exercised over an actual loopback WebSocket: registration,
bearer authentication, inbound dispatch into ``MessageEvent``, streamed drafts,
clarify and approval answers, and durable replay for a phone that was offline
when the turn finished. ``handle_message`` is replaced on the instance — that is
the documented seam the gateway itself installs.
"""
import asyncio
import importlib
import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location(
    "android_channel_transport", ROOT / "__init__.py", submodule_search_locations=[str(ROOT)]
)
assert _spec is not None and _spec.loader is not None
package = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = package
_spec.loader.exec_module(package)
adapter_mod = importlib.import_module(_spec.name + ".adapter")
storage_mod = importlib.import_module(_spec.name + ".storage")

from gateway.config import Platform, PlatformConfig  # noqa: E402
from gateway.platform_registry import PlatformEntry, platform_registry  # noqa: E402
from websockets.asyncio.client import connect  # noqa: E402
from websockets.exceptions import InvalidStatus  # noqa: E402


class _Ctx:
    def __init__(self):
        self.platform = None

    def register_platform(self, **kwargs):
        self.platform = kwargs
        return None


def setUpModule():
    ctx = _Ctx()
    package.register(ctx)
    assert ctx.platform is not None, "register() must call ctx.register_platform"
    kwargs = ctx.platform
    platform_registry.register(
        PlatformEntry(
            name=kwargs["name"],
            label=kwargs["label"],
            adapter_factory=kwargs["adapter_factory"],
            check_fn=kwargs["check_fn"],
            validate_config=kwargs.get("validate_config"),
            required_env=kwargs.get("required_env") or [],
            install_hint=kwargs.get("install_hint", ""),
            platform_hint=kwargs.get("platform_hint", ""),
            cron_deliver_env_var=kwargs.get("cron_deliver_env_var", ""),
            source="builtin",
        )
    )


class TransportTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self._saved = os.environ.pop("ANDROID_CHANNEL_TOKEN", None)
        self.events = []

    def tearDown(self):
        if self._saved is not None:
            os.environ["ANDROID_CHANNEL_TOKEN"] = self._saved

    def make_adapter(self, **extra):
        config = PlatformConfig(enabled=True, extra={"token": "secret", "host": "127.0.0.1", "port": 0, **extra})
        adapter = adapter_mod.AndroidChannelAdapter(config, root=Path(self._tmp.name))

        async def handler(event):
            self.events.append(event)

        adapter.handle_message = handler
        return adapter

    @staticmethod
    def url(adapter, path="/channel", token="secret"):
        suffix = f"?token={token}" if token else ""
        return f"ws://127.0.0.1:{adapter.bound_port}{path}{suffix}"

    async def start(self, **extra):
        adapter = self.make_adapter(**extra)
        self.assertTrue(await adapter.connect())
        self.addAsyncCleanup(adapter.disconnect)
        return adapter

    # ── registration and lifecycle ───────────────────────────────────────────

    def test_registers_as_a_platform_with_its_own_hint(self):
        self.assertEqual(package.register.__module__, _spec.name)
        self.assertEqual(Platform("android").value, "android")
        entry = platform_registry.get("android")
        self.assertIsNotNone(entry)
        self.assertTrue(entry.platform_hint)
        self.assertEqual(entry.cron_deliver_env_var, "ANDROID_HOME_CHANNEL")

    def test_gateway_registry_creates_the_adapter(self):
        config = PlatformConfig(enabled=True, extra={"token": "secret", "host": "127.0.0.1", "port": 0})
        adapter = platform_registry.create_adapter("android", config)
        self.assertIsNotNone(adapter)
        self.assertIsInstance(adapter, adapter_mod.AndroidChannelAdapter)
        self.assertEqual(adapter.platform.value, "android")

    def test_a_token_less_config_is_refused_before_construction(self):
        config = PlatformConfig(enabled=True, extra={})
        self.assertIsNone(platform_registry.create_adapter("android", config))

    async def test_refuses_to_start_without_a_token(self):
        config = PlatformConfig(enabled=True, extra={"host": "127.0.0.1", "port": 0})
        adapter = adapter_mod.AndroidChannelAdapter(config, root=Path(self._tmp.name))
        self.assertFalse(await adapter.connect())
        self.assertFalse(adapter.is_connected)

    async def test_rejects_bad_token_and_foreign_path(self):
        adapter = await self.start()
        with self.assertRaises(InvalidStatus) as bad:
            async with connect(self.url(adapter, token="wrong")):
                pass
        self.assertEqual(bad.exception.response.status_code, 401)
        with self.assertRaises(InvalidStatus) as wrong_path:
            async with connect(self.url(adapter, path="/api/ws")):
                pass
        self.assertEqual(wrong_path.exception.response.status_code, 404)

    async def test_query_token_is_accepted(self):
        adapter = await self.start()
        async with connect(self.url(adapter, token="secret")) as ws:
            await ws.send(json.dumps({"type": "ping"}))
            frame = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual(frame["type"], "pong")

    # ── inbound ──────────────────────────────────────────────────────────────

    async def test_inbound_message_becomes_a_gateway_event(self):
        adapter = await self.start()
        self.assertTrue(adapter.authorization_is_upstream)
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "message", "chat_id": "c1", "chat_name": "Work", "text": "привет"}))
            ack = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual(ack["type"], "accepted")
        self.assertEqual(ack["chat_id"], "c1")
        self.assertEqual(len(self.events), 1)
        event = self.events[0]
        self.assertEqual(event.text, "привет")
        self.assertEqual(event.source.platform.value, "android")
        self.assertEqual(event.source.chat_id, "c1")
        self.assertEqual(event.source.chat_name, "Work")
        self.assertEqual(event.source.chat_type, "dm")
        self.assertEqual(event.user_id, "android")
        self.assertEqual(adapter._store.name("c1"), "Work")

    async def test_rejects_a_message_without_text(self):
        adapter = await self.start()
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "message", "chat_id": "c1", "text": "   "}))
            frame = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual(frame["type"], "error")
        self.assertEqual(self.events, [])

    async def test_unknown_frame_is_reported_not_fatal(self):
        adapter = await self.start()
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "nope"}))
            frame = json.loads(await asyncio.wait_for(ws.recv(), 5))
            self.assertEqual(frame["type"], "error")
            await ws.send(json.dumps({"type": "ping"}))
            self.assertEqual(json.loads(await asyncio.wait_for(ws.recv(), 5))["type"], "pong")

    # ── outbound ─────────────────────────────────────────────────────────────

    async def test_send_pushes_a_sequenced_message(self):
        adapter = await self.start()
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "hello", "chats": ["c1"]}))
            hello = json.loads(await asyncio.wait_for(ws.recv(), 5))
            self.assertEqual(hello["type"], "hello")
            result = await adapter.send("c1", "готово")
            frame = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertTrue(result.success)
        self.assertEqual(frame["type"], "message")
        self.assertEqual(frame["text"], "готово")
        self.assertEqual(frame["seq"], 1)
        self.assertTrue(result.raw_response["delivered"])

    async def test_draft_and_working_state_frames(self):
        adapter = await self.start()
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "hello", "chats": ["c1"]}))
            await asyncio.wait_for(ws.recv(), 5)
            await adapter.send_draft("c1", 7, "част")
            await adapter.send_typing("c1")
            await adapter.stop_typing("c1")
            draft = json.loads(await asyncio.wait_for(ws.recv(), 5))
            working = json.loads(await asyncio.wait_for(ws.recv(), 5))
            idle = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual((draft["type"], draft["draft_id"], draft["text"]), ("draft", 7, "част"))
        self.assertEqual((working["type"], working["state"]), ("typing", "working"))
        self.assertEqual((idle["type"], idle["state"]), ("typing", "idle"))

    async def test_a_chat_only_reaches_its_own_subscribers(self):
        adapter = await self.start()
        async with connect(self.url(adapter)) as first, connect(self.url(adapter)) as second:
            await first.send(json.dumps({"type": "hello", "chats": ["c1"]}))
            await second.send(json.dumps({"type": "hello", "chats": ["c2"]}))
            await asyncio.wait_for(first.recv(), 5)
            await asyncio.wait_for(second.recv(), 5)
            await adapter.send("c2", "только второму")
            frame = json.loads(await asyncio.wait_for(second.recv(), 5))
            self.assertEqual(frame["chat_id"], "c2")
            with self.assertRaises(asyncio.TimeoutError):
                await asyncio.wait_for(first.recv(), 0.3)

    async def test_offline_turn_is_queued_and_replayed_on_hello(self):
        adapter = await self.start()
        result = await adapter.send("c1", "пока телефон спал")
        self.assertTrue(result.success)
        self.assertFalse(result.raw_response["delivered"])
        self.assertTrue(result.raw_response["queued"])
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "hello", "chats": ["c1"], "after_seq": 0}))
            hello = json.loads(await asyncio.wait_for(ws.recv(), 5))
            replay = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual(hello["chats"][0]["last_seq"], 1)
        self.assertEqual((replay["type"], replay["text"], replay["seq"]), ("message", "пока телефон спал", 1))
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "hello", "chats": ["c1"], "after_seq": 1}))
            await asyncio.wait_for(ws.recv(), 5)
            with self.assertRaises(asyncio.TimeoutError):
                await asyncio.wait_for(ws.recv(), 0.3)

    # ── interactive requests ─────────────────────────────────────────────────

    async def test_clarify_choices_and_answer_round_trip(self):
        adapter = await self.start()
        calls = []
        clarify = importlib.import_module("tools.clarify_gateway")
        original = clarify.resolve_gateway_clarify
        clarify.resolve_gateway_clarify = lambda cid, response: calls.append((cid, response)) or True
        self.addCleanup(setattr, clarify, "resolve_gateway_clarify", original)
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "hello", "chats": ["c1"]}))
            await asyncio.wait_for(ws.recv(), 5)
            await adapter.send_clarify("c1", "Какой сервер?", ["ams", "loffe"], "cl1", "s1")
            frame = json.loads(await asyncio.wait_for(ws.recv(), 5))
            self.assertEqual(frame["type"], "clarify")
            self.assertEqual(frame["choices"], ["ams", "loffe"])
            await ws.send(json.dumps({"type": "clarify", "clarify_id": "cl1", "choice": "loffe"}))
            ack = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual(ack, {"type": "clarify.ack", "clarify_id": "cl1", "resolved": True})
        self.assertEqual(calls, [("cl1", "loffe")])

    async def test_open_ended_clarify_is_a_plain_question(self):
        adapter = await self.start()
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "hello", "chats": ["c1"]}))
            await asyncio.wait_for(ws.recv(), 5)
            await adapter.send_clarify("c1", "Что дальше?", None, "cl2", "s1")
            frame = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual(frame["type"], "message")
        self.assertIn("Что дальше?", frame["text"])

    async def test_approval_card_and_answer_round_trip(self):
        adapter = await self.start()
        calls = []
        approval = importlib.import_module("tools.approval")
        original = approval.resolve_gateway_approval_card
        approval.resolve_gateway_approval_card = lambda key, choice, request_id: calls.append((key, choice, request_id)) or 1
        self.addCleanup(setattr, approval, "resolve_gateway_approval_card", original)
        prompt = SimpleNamespace(
            request_id="req1", session_key="sess", chat_id="c1", text="Выполнить rm -rf?",
            actions=[("Разрешить", "once", "primary"), ("Отклонить", "deny", "danger")],
        )
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "hello", "chats": ["c1"]}))
            await asyncio.wait_for(ws.recv(), 5)
            await adapter._send_exec_approval_prompt(prompt)
            frame = json.loads(await asyncio.wait_for(ws.recv(), 5))
            self.assertEqual((frame["type"], frame["request_id"]), ("approval", "req1"))
            self.assertEqual(frame["actions"][1], ["Отклонить", "deny", "danger"])
            await ws.send(json.dumps({"type": "approval", "request_id": "req1", "choice": "once"}))
            ack = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual(ack, {"type": "approval.ack", "request_id": "req1", "resolved": True})
        self.assertEqual(calls, [("sess", "once", "req1")])

    async def test_stale_approval_card_is_refused(self):
        adapter = await self.start()
        async with connect(self.url(adapter)) as ws:
            await ws.send(json.dumps({"type": "approval", "request_id": "ghost", "choice": "once"}))
            frame = json.loads(await asyncio.wait_for(ws.recv(), 5))
        self.assertEqual(frame["type"], "error")


class StorageTest(unittest.TestCase):
    def test_keeps_a_bounded_outbox_per_chat(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = storage_mod.ChannelStore(Path(tmp), keep=3)
            store.touch_chat("c1", "Work")
            for index in range(5):
                store.append("c1", {"type": "message", "text": str(index)})
            store.touch_chat("c2", "Home")
            store.append("c2", {"type": "message", "text": "other"})
            replay = [frame["text"] for frame in store.replay("c1", 0)]
            self.assertEqual(replay, ["2", "3", "4"])
            self.assertEqual(store.last_seq("c1"), 5)
            self.assertEqual(store.last_seq("c2"), 6)
            self.assertEqual({chat["chat_id"] for chat in store.chats()}, {"c1", "c2"})
            self.assertEqual(store.name("c1"), "Work")


if __name__ == "__main__":
    unittest.main()
