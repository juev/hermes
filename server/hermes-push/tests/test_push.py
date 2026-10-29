"""Offline tests of hermes-push: storage, payload, and a push to a local endpoint decrypted with http_ece.

Run: uv run --no-project --with cryptography --with http_ece python -m unittest discover -s tests
(from the plugin directory).
"""

import importlib.util
import json
import sys
import tempfile
import threading
import unittest
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
        hooks = {}
        ctx = SimpleNamespace(register_hook=hooks.__setitem__, current_cron_execution=lambda: None)
        plugin.register(ctx)
        sent = []
        # The send runs here rather than on a thread, so a push the hook started is seen at once.
        inline = SimpleNamespace(Thread=lambda target, args, **_: SimpleNamespace(start=lambda: target(*args)))
        with mock.patch.object(plugin.store, "subscriptions", return_value=[{"endpoint": self.endpoint}]), \
                mock.patch.object(plugin, "deliver", sent.append), mock.patch.object(plugin, "threading", inline):
            hooks["post_llm_call"](session_id="child", assistant_response="Found three files", platform="subagent")
            hooks["post_llm_call"](session_id="chat", assistant_response="Done", platform="tui")
        self.assertEqual([m["session_id"] for m in sent], ["chat"])


if __name__ == "__main__":
    unittest.main()
