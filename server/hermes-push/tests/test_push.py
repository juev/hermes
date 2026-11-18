"""Offline tests of hermes-push: storage, payload, and a push to a local endpoint decrypted with http_ece.

Run: uv run --no-project --with cryptography --with http_ece python -m unittest discover -s tests
(from the plugin directory).
"""

import importlib.util
import json
import sqlite3
import sys
import tempfile
import threading
import unittest
from contextlib import closing
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("hermes_push", ROOT / "__init__.py", submodule_search_locations=[str(ROOT)])
plugin = importlib.util.module_from_spec(spec)
sys.modules["hermes_push"] = plugin
spec.loader.exec_module(plugin)
store, webpush = plugin.store, plugin.webpush

import http_ece  # noqa: E402
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402


def turn_hook(home, execution=None):
    hooks = {}
    plugin.register(SimpleNamespace(register_hook=hooks.__setitem__, current_cron_execution=lambda: execution))
    constants = {"hermes_constants": SimpleNamespace(get_hermes_home=lambda: home)}
    return hooks["post_llm_call"], mock.patch.dict(sys.modules, constants)


class Endpoint(BaseHTTPRequestHandler):
    status = 201
    received: list = []

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        Endpoint.received.append((dict(self.headers), body))
        self.send_response(Endpoint.status)
        self.end_headers()

    def log_message(self, *args):
        pass


class PushTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        Endpoint.received = []
        Endpoint.status = 201
        self.server = HTTPServer(("127.0.0.1", 0), Endpoint)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.endpoint = f"http://127.0.0.1:{self.server.server_port}/up/abc"
        self.phone = ec.generate_private_key(ec.SECP256R1())
        self.p256dh = webpush.b64url(self.phone.public_key().public_bytes(
            serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint))
        self.auth = webpush.b64url(b"sixteen byte key")

    def tearDown(self):
        self.server.shutdown()

    def test_subscriptions_are_replaced_by_endpoint_and_removed(self):
        store.subscribe("https://e/1", "k1", "a1", "phone", root=self.root)
        store.subscribe("https://e/1", "k2", "a2", "phone", root=self.root)
        self.assertEqual([s["p256dh"] for s in store.subscriptions(self.root)], ["k2"])
        self.assertTrue(store.unsubscribe("https://e/1", root=self.root))
        self.assertEqual(store.subscriptions(self.root), [])

    def test_a_phone_back_with_a_new_endpoint_replaces_its_old_ones(self):
        # Made before the app sent the id of the device: two of this phone, one of another.
        store.subscribe("https://e/1", "k1", "a1", "CPH2747", root=self.root)
        store.subscribe("https://e/2", "k2", "a2", "CPH2747", root=self.root)
        store.subscribe("https://e/3", "k3", "a3", "Pixel 9", root=self.root)
        store.subscribe("https://e/4", "k4", "a4", "CPH2747", device="d1", root=self.root)
        self.assertEqual([s["endpoint"] for s in store.subscriptions(self.root)], ["https://e/3", "https://e/4"])
        # Same model, another phone: both stay. The first phone after a reinstall: only its new one.
        store.subscribe("https://e/5", "k5", "a5", "CPH2747", device="d2", root=self.root)
        store.subscribe("https://e/6", "k6", "a6", "CPH2747", device="d1", root=self.root)
        self.assertEqual([s["endpoint"] for s in store.subscriptions(self.root)], ["https://e/3", "https://e/5", "https://e/6"])

    def test_without_the_id_of_the_device_only_the_endpoint_is_replaced(self):
        store.subscribe("https://e/1", "k1", "a1", "CPH2747", root=self.root)
        store.subscribe("https://e/2", "k2", "a2", "CPH2747", root=self.root)
        self.assertEqual([s["endpoint"] for s in store.subscriptions(self.root)], ["https://e/1", "https://e/2"])

    def test_the_key_is_made_once_and_kept(self):
        self.assertIsNone(store.vapid(self.root))
        first = store.vapid(self.root, create=True).public_key
        self.assertEqual(store.vapid(self.root).public_key, first)

    def test_a_turn_reaches_the_phone_encrypted(self):
        store.vapid(self.root, create=True)
        store.subscribe(self.endpoint, self.p256dh, self.auth, root=self.root)
        message = plugin.payload("s1", "telegram", "Ответ  агента\nв две строки", "digest")
        self.assertEqual(plugin.deliver(message, root=self.root), 1)
        headers, body = Endpoint.received[0]
        self.assertEqual(headers["Content-Encoding"], "aes128gcm")
        self.assertTrue(headers["Authorization"].startswith("vapid t="))
        plain = http_ece.decrypt(body, private_key=self.phone, auth_secret=webpush.unb64url(self.auth), version="aes128gcm")
        self.assertEqual(json.loads(plain), {"type": "turn", "session_id": "s1", "platform": "telegram", "job": "digest", "preview": "Ответ агента в две строки"})

    def test_a_gone_endpoint_is_dropped(self):
        store.vapid(self.root, create=True)
        store.subscribe(self.endpoint, self.p256dh, self.auth, root=self.root)
        Endpoint.status = 410
        self.assertEqual(plugin.deliver({"type": "test"}, root=self.root), 0)
        self.assertEqual(store.subscriptions(self.root), [])

    def test_nothing_is_sent_without_a_key(self):
        store.subscribe(self.endpoint, self.p256dh, self.auth, root=self.root)
        self.assertEqual(plugin.deliver({"type": "test"}, root=self.root), 0)
        self.assertEqual(Endpoint.received, [])

    def test_a_long_answer_is_cut(self):
        self.assertEqual(len(plugin.payload("s", "tui", "x" * 5000, None)["preview"]), plugin.PREVIEW_CHARS)

    def test_only_turns_of_chats_notify_not_their_subagents(self):
        hook, profile = turn_hook(self.root)
        sent = []
        # The send runs here rather than on a thread, so a push the hook started is seen at once.
        inline = SimpleNamespace(Thread=lambda target, args, **_: SimpleNamespace(start=lambda: target(*args)))
        with profile, mock.patch.object(plugin.store, "subscriptions", return_value=[{"endpoint": self.endpoint}]), \
                mock.patch.object(plugin, "deliver", sent.append), mock.patch.object(plugin, "threading", inline):
            hook(session_id="child", assistant_response="Found three files", platform="subagent")
            hook(session_id="chat", assistant_response="Done", platform="tui")
        self.assertEqual([m["session_id"] for m in sent], ["chat"])

    def test_only_eligible_turns_reach_the_test_subscription(self):
        store.vapid(self.root, create=True)
        store.subscribe(self.endpoint, self.p256dh, self.auth, root=self.root)
        with closing(sqlite3.connect(self.root / "state.db")) as db, db:
            db.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT, created_source TEXT)")
            db.executemany("INSERT INTO sessions VALUES (?, ?, ?)", [
                ("smoke", "local", "oneshot"),
                ("chat", "local", "cli"),
                ("phone", "api_server", "api_server"),
                ("scheduled", "cron", "cron"),
            ])
        hook, profile = turn_hook(self.root)
        cron_hook, _ = turn_hook(self.root, SimpleNamespace(job_name="Digest"))
        # Inline scheduling makes absence of an HTTP request observable without a sleep.
        def inline(target, args, **_):
            return SimpleNamespace(start=lambda: target(*args, root=self.root))

        with profile, mock.patch.object(plugin.store, "data_dir", return_value=self.root), \
                mock.patch.object(plugin, "threading", SimpleNamespace(Thread=inline)):
            hook(session_id="smoke", platform="local", assistant_response="No push")
            self.assertEqual(Endpoint.received, [])
            hook(session_id="chat", platform="local", assistant_response="Interactive reply")
            hook(session_id="phone", platform="api_server", assistant_response="Android reply")
            cron_hook(session_id="scheduled", platform="cron", assistant_response="Scheduled report")
            self.assertEqual(plugin.deliver({"type": "test"}, root=self.root), 1)
        auth = webpush.unb64url(self.auth)
        received = [json.loads(http_ece.decrypt(body, private_key=self.phone, auth_secret=auth, version="aes128gcm"))
                    for _, body in Endpoint.received]
        self.assertEqual([(msg.get("session_id"), msg.get("platform"), msg.get("job")) for msg in received], [
            ("chat", "local", None), ("phone", "api_server", None), ("scheduled", "cron", "Digest"),
            (None, None, None),
        ])
        self.assertEqual(received[-1], {"type": "test"})


class TurnProvenanceTest(unittest.TestCase):
    def test_only_standalone_one_shots_are_silent(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            with closing(sqlite3.connect(home / "state.db")) as db, db:
                db.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT, created_source TEXT)")
                db.executemany("INSERT INTO sessions VALUES (?, ?, ?)", [
                    ("direct", "oneshot", None),
                    ("gateway", "local", "oneshot"),
                    ("chat", "local", "cli"),
                ])
            hook, profile = turn_hook(home)
            with profile, \
                    mock.patch.object(plugin.store, "subscriptions", return_value=[{}]), \
                    mock.patch.object(plugin, "threading") as scheduler:
                for session_id, platform in [("direct", "cli"), ("gateway", "local"), ("chat", "local")]:
                    hook(session_id=session_id, platform=platform, assistant_response="Done")
                scheduled = [call.kwargs["args"][0]["session_id"] for call in scheduler.Thread.call_args_list]
            self.assertEqual(scheduled, ["chat"])

    def test_old_or_missing_state_db_preserves_normal_push(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            db_path = home / "state.db"
            hook, profile = turn_hook(home)
            with profile, mock.patch.object(plugin.store, "subscriptions", return_value=[{}]), \
                    mock.patch.object(plugin, "threading") as scheduler:
                hook(session_id="explicit", platform="oneshot")
                scheduler.Thread.assert_not_called()
                hook(session_id="unknown", platform="cli")
                self.assertFalse(db_path.exists())
                with closing(sqlite3.connect(db_path)) as db, db:
                    db.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT)")
                    db.executemany("INSERT INTO sessions VALUES (?, ?)", [("smoke", "oneshot"), ("chat", "cli")])
                hook(session_id="smoke", platform="cli")
                hook(session_id="chat", platform="cli")
                scheduled = [call.kwargs["args"][0]["session_id"] for call in scheduler.Thread.call_args_list]
            self.assertEqual(scheduled, ["unknown", "chat"])


if __name__ == "__main__":
    unittest.main()
