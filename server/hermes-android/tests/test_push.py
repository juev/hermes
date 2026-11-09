"""Real private DB/crypto, synthetic outbound delivery observer (no external push)."""
import importlib
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
import threading

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_push_plugin", ROOT / "__init__.py", submodule_search_locations=[str(ROOT)])
assert spec is not None and spec.loader is not None
package = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = package
spec.loader.exec_module(package)
push = importlib.import_module("android_push_plugin.push")
Registry = importlib.import_module("android_push_plugin.registry").Registry


class PushTest(unittest.TestCase):
    def test_installed_hooks_dispatch_only_registered_channel_turns_and_preserve_compaction_aliases(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            root = Path(directory)
            registry = Registry(root)
            registry.claim("alice", "owned")
            push.PushService(registry).subscribe("alice", "https://push.example/a", "fixture-public", "fixture-auth")
            sent = []
            delivered = threading.Event()

            def sender(endpoint, p256dh, auth, message, key, subject):
                sent.append((threading.current_thread(), message))
                delivered.set()

            class Context:
                def __init__(self):
                    self.hooks = {}

                def register_hook(self, name, callback):
                    self.hooks[name] = callback

            ctx = Context()
            package.register(ctx, root=root, sender=sender)
            self.assertIn("post_llm_call", ctx.hooks, "The installed plugin needs a real disconnected-phone completion path")
            self.assertIn("post_compaction", ctx.hooks)
            ctx.hooks["post_llm_call"](session_id="telegram", assistant_response="PRIVATE_FOREIGN", platform="telegram")
            ctx.hooks["post_compaction"](old_session_id="owned", session_id="physical")
            ctx.hooks["post_llm_call"](session_id="physical", assistant_response="Readable", platform="tool")
            self.assertTrue(delivered.wait(5))
            sent[0][0].join(timeout=5)
            self.assertFalse(sent[0][0].is_alive())
            self.assertEqual(1, len(sent))
            self.assertEqual("owned", sent[0][1]["session_id"])
            self.assertEqual("Readable", sent[0][1]["preview"])

    def test_completions_notify_only_the_logical_channels_owner_after_reconnect_or_compaction(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            root = Path(directory)
            registry = Registry(root)
            registry.claim("alice", "owned")
            registry.claim("bob", "other")
            sent = []

            def sender(endpoint, p256dh, auth, message, key, subject):
                sent.append((endpoint, message))

            service = push.PushService(registry, sender=sender)
            service.subscribe("alice", "https://push.example/a", "fixture-public-a", "fixture-auth-a", device="device-a")
            service.subscribe("bob", "https://push.example/b", "fixture-public-b", "fixture-auth-b", device="device-b")
            self.assertEqual(1, service.deliver("owned", "Readable final"), "Channel completions must reach their owner without a live phone socket")
            self.assertEqual(["https://push.example/a"], [row[0] for row in sent])
            self.assertEqual("android-minimal-v1", sent[0][1]["channel"])
            self.assertEqual("owned", sent[0][1]["session_id"])
            self.assertEqual("Readable final", sent[0][1]["preview"])
            self.assertEqual(0, service.deliver("telegram-foreign", "PRIVATE_FOREIGN"))
            self.assertEqual(1, len(sent))
            registry.alias("owned", "physical")
            restarted = push.PushService(Registry(root), sender=sender)
            self.assertEqual(1, restarted.deliver("physical", "After compaction"))
            self.assertEqual("owned", sent[-1][1]["session_id"])
            self.assertEqual(service.key("alice").public_key, restarted.key("alice").public_key)
            self.assertFalse(restarted.unsubscribe("bob", "https://push.example/a"))
            self.assertTrue(restarted.unsubscribe("alice", "https://push.example/a"))
            self.assertEqual(0, restarted.deliver("owned", "No subscribers"))


if __name__ == "__main__":
    unittest.main()
