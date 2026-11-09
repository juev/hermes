"""Synthetic adapter boundary tests; native feasibility is tested separately."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

import importlib
import sys

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_bridge_plugin", ROOT / "__init__.py", submodule_search_locations=[str(ROOT)])
assert spec is not None and spec.loader is not None
package = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = package
spec.loader.exec_module(package)
bridge = importlib.import_module("android_bridge_plugin.bridge")
registry = importlib.import_module("android_bridge_plugin.registry")


class NativeFixture:
    def __init__(self):
        self.calls = []
        self.canonical = False
        self.responses = []
        self.snapshot = {"session_id": "live-owned", "stored_session_id": "stored-owned", "revision": 7, "messages": [], "info": {"model": "fixture-model"}, "running": False}

    async def respond(self, frame):
        self.responses.append(frame)

    async def rpc(self, method, **params):
        self.calls.append((method, params))
        if method in ("session.create", "session.resume"):
            return self.snapshot
        return {"status": "streaming", "user_row_id": 1}


class BridgeTest(unittest.IsolatedAsyncioTestCase):
    async def test_delivered_connection_card_accepts_only_its_scoped_operation_actions(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            await proxy.request("session.create", {})
            owner = {"type": "session", "session_id": "live-owned"}
            proxy.forward({"method": "event", "params": {"type": "connection.request", "session_id": "live-owned", "payload": {"owner": owner, "op_id": "op1", "targets": []}}})
            actions = ["connection.respond", "connectors.operation.status", "connectors.operation.wake"]
            accepted = []
            for method in actions:
                try:
                    await proxy.request(method, {"owner": owner, "op_id": "op1", "result": {}} if method == "connection.respond" else {"owner": owner, "op_id": "op1"})
                    accepted.append(method)
                except PermissionError:
                    pass
            self.assertEqual(actions, accepted, "Required actions belong to the delivered operation owner, not a nonexistent root session_id")
            before = len(native.calls)
            for method in actions:
                with self.assertRaises(PermissionError):
                    await proxy.request(method, {"owner": owner, "op_id": "guessed-foreign-operation"})
                with self.assertRaises(PermissionError):
                    await proxy.request(method, {"owner": {"type": "session", "session_id": "foreign"}, "op_id": "op1"})
            self.assertEqual(before, len(native.calls))

    async def test_reconnect_restores_connection_action_authority_from_pending_card(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            store = registry.Registry(Path(directory))
            await bridge.Bridge(native, store, "alice").request("session.create", {})
            owner = {"type": "session", "session_id": "live-owned"}
            native.snapshot["pending_connection"] = {"owner": owner, "op_id": "restored-op", "targets": []}
            resumed = bridge.Bridge(native, store, "alice")
            await resumed.request("session.resume", {"session_id": "stored-owned"})
            accepted = False
            try:
                await resumed.request("connectors.operation.wake", {"owner": owner, "op_id": "restored-op"})
                accepted = True
            except PermissionError:
                pass
            self.assertTrue(accepted, "An owned card restored by resume must regain its operation authority on the new transport")

    async def test_account_settings_can_wake_only_the_explicitly_opened_connection(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            original = native.rpc
            async def connect(method, **params):
                if method == "connectors.connect":
                    native.calls.append((method, params))
                    return {"op_id": "account-op", "targets": []}
                return await original(method, **params)
            native.rpc = connect
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            params = {"owner": {"type": "account"}, "op_id": "account-op"}
            with self.assertRaises(PermissionError):
                await proxy.request("connectors.operation.wake", params)
            await proxy.request("connectors.connect", {"owner": params["owner"], "connectors": ["fixture"]})
            accepted = False
            try:
                await proxy.request("connectors.operation.wake", params)
                accepted = True
            except PermissionError:
                pass
            self.assertTrue(accepted, "Explicit account settings must remain usable without authorizing guessed session operations")

    async def test_canonical_title_uses_revision_fenced_public_mutation_not_legacy_rpc(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            native.canonical = True
            native.snapshot.update(revision=7, execution_generation=2, subscription_id="sub1")
            original = native.rpc
            async def canonical(method, **params):
                if method == "session.title":
                    raise RuntimeError("Method not found (-32601)")
                if method == "session.mutate":
                    native.calls.append((method, params))
                    return {"session_id": "live-owned", "operation": "rename", "revision": 8, "title": params["payload"]["title"]}
                return await original(method, **params)
            native.rpc = canonical
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            created = None
            try:
                created = await proxy.request("session.create", {"title": "Fresh label"})
            except RuntimeError:
                pass
            self.assertIsNotNone(created, "Canonical creation must not call the unsupported session.title legacy RPC")
            mutation = next(params for method, params in native.calls if method == "session.mutate")
            self.assertEqual("rename", mutation["operation"])
            self.assertEqual(7, mutation["expected_revision"])
            self.assertEqual("Fresh label", mutation["payload"]["title"])
            self.assertTrue(mutation["request_id"])
            self.assertNotIn("expected_generation", mutation, "Rename is metadata, not a destructive generation change")

    async def test_canonical_model_pick_preserves_selection_and_requires_explicit_guard_confirmation(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            native.canonical = True
            native.snapshot.update(execution_generation=2, subscription_id="sub1")
            original = native.rpc
            async def canonical(method, **params):
                if method == "config.set":
                    raise RuntimeError("use_session_mutation_model")
                if method == "session.mutate":
                    native.calls.append((method, params))
                    return {"status": "applied"} if params["payload"].get("confirm") == "guard-token" else {"status": "confirmation_required", "confirm": "guard-token", "confirm_message": "Confirm this model"}
                return await original(method, **params)
            native.rpc = canonical
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            await proxy.request("session.create", {})
            params = {"session_id": "live-owned", "key": "model", "value": "picked-model --provider picked-provider"}
            decision = {}
            try:
                decision = await proxy.request("config.set", params)
            except RuntimeError:
                pass
            self.assertTrue(decision.get("confirm_required"), "A canonical model guard must reach the existing Android confirmation UI")
            first = native.calls[-1][1]
            self.assertEqual({"model": "picked-model", "provider": "picked-provider"}, first["payload"])
            self.assertEqual(7, first["expected_revision"])
            self.assertEqual(2, first["expected_generation"])
            self.assertNotIn("guard-token", str(decision))
            await proxy.request("config.set", {**params, "confirm_expensive_model": True})
            second = native.calls[-1][1]
            self.assertEqual("guard-token", second["payload"]["confirm"])
            self.assertEqual(first["expected_revision"], second["expected_revision"])
            self.assertEqual(first["expected_generation"], second["expected_generation"])

    async def test_canonical_retry_rewinds_the_last_human_row_under_snapshot_fences(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            native.canonical = True
            native.snapshot.update(execution_generation=2, subscription_id="sub1", messages=[
                {"row_id": 1, "role": "user", "text": "First"},
                {"row_id": 2, "role": "user", "text": "Archived", "archived": True},
                {"row_id": 3, "role": "user", "text": "Retry this"},
                {"row_id": 4, "role": "user", "text": "[System: background status]"},
                {"row_id": 5, "role": "assistant", "text": "Answer"}])
            original = native.rpc
            async def canonical(method, **params):
                if method == "session.undo":
                    raise RuntimeError("Method not found (-32601)")
                if method == "session.mutate":
                    native.calls.append((method, params))
                    return {"session_id": "live-owned", "operation": "rewind", "revision": 8, "rewound_count": 1}
                return await original(method, **params)
            native.rpc = canonical
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            await proxy.request("session.create", {})
            accepted = False
            try:
                await proxy.request("session.undo", {"session_id": "live-owned", "intent": "retry"})
                accepted = True
            except RuntimeError:
                pass
            self.assertTrue(accepted, "Retry must use the canonical rewind contract, not the unavailable legacy verb")
            mutation = native.calls[-1][1]
            self.assertEqual("rewind", mutation["operation"])
            self.assertEqual({"target_message_id": 3}, mutation["payload"])
            self.assertEqual(7, mutation["expected_revision"])
            self.assertEqual(2, mutation["expected_generation"])
            self.assertTrue(mutation["request_id"])

    async def test_authoritative_resume_keeps_one_logical_identity_after_native_compaction(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            store = registry.Registry(Path(directory))
            await bridge.Bridge(native, store, "alice").request("session.create", {})
            native.snapshot["stored_session_id"] = "physical-tip"
            resumed = await bridge.Bridge(native, store, "alice").request("session.resume", {"session_id": "stored-owned"})
            self.assertEqual("stored-owned", resumed["session_key"], "A native compaction tip is an alias, not a new Android conversation")
            self.assertEqual("stored-owned", resumed["stored_session_id"])
            self.assertEqual(["stored-owned"], store.sessions("alice"))
            self.assertTrue(store.owns("alice", "physical-tip"))

    async def test_canonical_resume_stop_and_close_preserve_native_lifecycle_fences(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            native.canonical = True
            native.snapshot.update(subscription_id="sub1", execution_generation=5)
            store = registry.Registry(Path(directory))
            proxy = bridge.Bridge(native, store, "alice")
            await proxy.request("session.create", {})
            await proxy.request("session.resume", {"session_id": "stored-owned", "close_on_disconnect": False, "inline_images": False, "omit_messages": True})
            self.assertNotIn("close_on_disconnect", native.calls[-1][1], "Legacy viewer flags must not enter the closed canonical resume contract")
            self.assertNotIn("inline_images", native.calls[-1][1])
            proxy.forward({"method": "event", "params": {"type": "message.start", "session_id": "live-owned", "execution_generation": 6, "payload": {}}})
            await proxy.request("session.interrupt", {"session_id": "live-owned"})
            self.assertEqual(6, native.calls[-1][1].get("execution_generation"))
            proxy.forward({"id": "srq-close", "method": "clarify", "params": {"session_id": "live-owned", "questions": []}})
            await proxy.request("session.close", {"session_id": "live-owned"})
            self.assertEqual("session.detach", native.calls[-1][0])
            self.assertEqual("sub1", native.calls[-1][1].get("subscription_id"))
            with self.assertRaises(PermissionError):
                await proxy.request("prompt.submit", {"session_id": "live-owned", "text": "Stale controller"})
            with self.assertRaises(PermissionError):
                await proxy.respond({"id": "srq-close", "result": {"outcome": "cancelled"}})
            await proxy.request("gateway.ping", {})
            self.assertEqual("ping", native.calls[-1][0])

    async def test_explicit_model_selection_is_not_silently_discarded(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            native.canonical = True
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            await proxy.request("session.create", {"model": "selected-model", "provider": "selected-provider"})
            self.assertEqual("selected-model", native.calls[0][1].get("model"))
            self.assertEqual("selected-provider", native.calls[0][1].get("provider"))

    async def test_alternate_native_selectors_cannot_override_a_channel_identity(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            await proxy.request("session.create", {})
            before = len(native.calls)
            attempts = [
                ("session.info", {"session_id": "live-owned", "profile": "another-profile"}),
                ("session.resume", {"session_id": "stored-owned", "title": "Foreign title"}),
                ("session.resume", {"session_id": "stored-owned", "latest": "cli"}),
                ("session.archive", {"session_id": "live-owned", "session_key": "foreign", "archived": True}),
                ("connectors.list", {"owner": {"type": "session", "session_id": "foreign"}}),
            ]
            for method, params in attempts:
                with self.subTest(method=method, params=params):
                    with self.assertRaises(PermissionError, msg="Secondary selectors must not escape the server-owned channel"):
                        await proxy.request(method, params)
            self.assertEqual(before, len(native.calls))

    async def test_create_title_cannot_adopt_an_existing_foreign_conversation(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            native.canonical = True
            original = native.rpc

            async def title_resolving_native(method, **params):
                if method == "session.create" and params.get("title") == "Existing foreign title":
                    native.calls.append((method, params))
                    return {"session_id": "foreign", "stored_session_id": "foreign", "messages": []}
                return await original(method, **params)

            native.rpc = title_resolving_native
            store = registry.Registry(Path(directory))
            proxy = bridge.Bridge(native, store, "alice")
            created = await proxy.request("session.create", {"title": "Existing foreign title"})
            self.assertFalse(store.owns("alice", "foreign"), "Native title-as-identity creation must not import an unrelated conversation into the channel")
            self.assertEqual("stored-owned", created["stored_session_id"])
            labels = [params for method, params in native.calls if method in ("session.title", "session.mutate")]
            self.assertEqual("live-owned", labels[-1]["session_id"])
            self.assertEqual("Existing foreign title", labels[-1].get("payload", labels[-1])["title"])

    async def test_rpc_boundary_denies_unknown_methods_and_sanitizes_submission_receipts(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            await proxy.request("session.create", {})
            before = len(native.calls)
            with self.assertRaises(PermissionError, msg="A joined session is not permission for arbitrary native RPC"):
                await proxy.request("session.search", {"session_id": "live-owned", "query": "foreign"})
            self.assertEqual(before, len(native.calls))
            original = native.rpc

            async def receipt(method, **params):
                if method == "prompt.submit":
                    return {"status": "queued", "admission_id": "a1", "user_row_id": 7, "raw_tool_result": "PRIVATE"}
                return await original(method, **params)

            native.rpc = receipt
            ack = await proxy.request("prompt.submit", {"session_id": "live-owned", "text": "Fixture"})
            self.assertEqual("a1", ack["admission_id"])
            self.assertNotIn("raw_tool_result", ack)

    async def test_canonical_gateway_uses_supported_creation_and_durable_submission(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            native.canonical = True
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            created = await proxy.request("session.create", {"source": "android", "title": "Channel test", "close_on_disconnect": False})
            create_params = next(params for method, params in native.calls if method == "session.create")
            self.assertEqual("tool", create_params["source"], "An installed plugin must use a supported native source, not forge a platform")
            self.assertTrue(create_params.get("request_id"))
            self.assertNotIn("close_on_disconnect", create_params)
            self.assertEqual("stored-owned", created["session_key"])
            await proxy.request("prompt.submit", {"session_id": "live-owned", "text": "Fixture"})
            self.assertTrue(native.calls[-1][1].get("submission_id"))
            self.assertIs(native.calls[-1][1].get("clarify_batch"), True)

    async def test_resume_projects_snapshot_and_revalidates_request_until_native_withdrawal(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            store = registry.Registry(Path(directory))
            await bridge.Bridge(native, store, "alice").request("session.create", {})
            native.snapshot["messages"] = [{"role": "assistant", "text": "Visible", "reasoning": "PRIVATE"}]
            native.snapshot["open_requests"] = [{"id": "srq1", "method": "clarify", "params": {"session_id": "live-owned", "questions": []}}]
            proxy = bridge.Bridge(native, store, "alice")
            joined = await proxy.request("session.resume", {"session_id": "stored-owned"})
            self.assertNotIn("reasoning", joined["messages"][0], "Snapshots must cross the same minimal boundary as live events")
            response = {"jsonrpc": "2.0", "id": "srq1", "result": {"outcome": "cancelled"}}
            await proxy.respond(response)
            self.assertEqual([response], native.responses)
            question = {"jsonrpc": "2.0", "id": "srq2", "method": "clarify", "params": {"session_id": "live-owned", "questions": []}}
            proxy.forward(question)
            proxy.forward({"method": "event", "params": {"type": "request.cancel", "session_id": "live-owned", "payload": {"id": "srq2", "method": "clarify", "reason": "timeout"}}})
            with self.assertRaises(PermissionError):
                await proxy.respond({"jsonrpc": "2.0", "id": "srq2", "result": {"outcome": "cancelled"}})
            self.assertEqual([response], native.responses)

    async def test_live_channel_rejects_foreign_frames_and_binds_answers_to_delivered_requests(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            native = NativeFixture()
            proxy = bridge.Bridge(native, registry.Registry(Path(directory)), "alice")
            await proxy.request("session.create", {"source": "android"})
            foreign = {"method": "event", "params": {"session_id": "telegram", "type": "message.delta", "payload": {"text": "PRIVATE"}}}
            self.assertIsNone(proxy.forward(foreign), "Unrelated native events must not reach this channel")
            own = {"method": "event", "params": {"session_id": "live-owned", "type": "message.delta", "seq": 3, "payload": {"text": "Visible", "raw": "PRIVATE"}}}
            self.assertEqual({"text": "Visible"}, proxy.forward(own)["params"]["payload"])
            tool = {"method": "event", "params": {"session_id": "live-owned", "type": "tool.complete", "payload": {"result": "PRIVATE"}}}
            self.assertIsNone(proxy.forward(tool))
            answer = {"jsonrpc": "2.0", "id": "srq1", "result": {"outcome": "cancelled"}}
            with self.assertRaises(PermissionError):
                await proxy.respond(answer)
            question = {"jsonrpc": "2.0", "id": "srq1", "method": "clarify", "params": {"session_id": "live-owned", "questions": []}}
            self.assertEqual(question, proxy.forward(question))
            await proxy.respond(answer)
            with self.assertRaises(PermissionError):
                await proxy.respond(answer)
            self.assertEqual([answer], native.responses)

    async def test_only_server_claimed_sessions_can_be_resumed_after_reconnect(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            store = registry.Registry(Path(directory))
            native = NativeFixture()
            first = bridge.Bridge(native, store, "alice")
            created = await first.request("session.create", {"source": "android"})
            self.assertEqual("live-owned", created["session_id"])
            reconnect = bridge.Bridge(native, store, "alice")
            before = len(native.calls)
            with self.assertRaises(PermissionError, msg="Foreign sessions must be refused before reaching native API"):
                await reconnect.request("session.resume", {"session_id": "telegram-session"})
            self.assertEqual(before, len(native.calls))
            joined = await reconnect.request("session.resume", {"session_id": "stored-owned"})
            self.assertEqual("live-owned", joined["session_id"])
            self.assertTrue(store.owns("alice", "stored-owned"))
            with self.assertRaises(PermissionError):
                await bridge.Bridge(native, store, "bob").request("session.resume", {"session_id": "stored-owned"})


if __name__ == "__main__":
    unittest.main()
