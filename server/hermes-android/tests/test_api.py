"""HTTP integration using synthetic authentication/native fixtures, not a live deployment."""
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

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_plugin", ROOT / "__init__.py", submodule_search_locations=[str(ROOT)])
assert spec is not None and spec.loader is not None
package = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = package
spec.loader.exec_module(package)
api = importlib.import_module("android_plugin.api")
registry = importlib.import_module("android_plugin.registry")


class ApiTest(unittest.TestCase):
    def test_compaction_history_and_discovery_keep_the_server_owned_logical_identity(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            store = registry.Registry(Path(directory))
            owner = json.dumps(["fixture", "", "alice"], separators=(",", ":"))
            store.claim(owner, "logical")
            store.alias("logical", "physical")
            current = ["physical"]
            async def native(request, method, path, **kwargs):
                if path.endswith("/messages"):
                    return {"session_id": current[0], "messages": [{"id": 1, "role": "assistant", "content": "Readable"}], "pagination": {"returned": 1}}
                return {"id": "physical", "title": "Owned", "last_active": 1}
            app = FastAPI()
            @app.middleware("http")
            async def signed_in(request, call_next):
                request.state.session = SimpleNamespace(user_id="alice", provider="fixture", issuer="")
                return await call_next(request)
            app.include_router(api.make_router(store, native), prefix="/plugin")
            with TestClient(app, headers={"Authorization": "Bearer fixture-alice"}) as client:
                history = client.get("/plugin/api/sessions/logical/messages")
                self.assertEqual(200, history.status_code, "The trusted compaction alias must not make the logical history permanently unavailable")
                self.assertEqual("logical", history.json()["session_id"])
                self.assertEqual("logical", client.get("/plugin/api/sessions").json()["sessions"][0]["id"])
                self.assertEqual("logical", client.get("/plugin/api/sessions/logical").json()["id"])
                hit = client.get("/plugin/api/sessions/search?q=Readable").json()["results"][0]
                self.assertEqual(("logical", "logical"), (hit["id"], hit["session_id"]))
                store.claim(owner, "another-owned-conversation")
                current[0] = "another-owned-conversation"
                self.assertEqual(409, client.get("/plugin/api/sessions/logical/messages").status_code)

    def test_push_registration_and_test_are_principal_scoped_not_global_broadcasts(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            store = registry.Registry(Path(directory))
            push_module = importlib.import_module("android_plugin.push")
            sent = []
            service = push_module.PushService(store, sender=lambda endpoint, p256dh, auth, message, key, subject: sent.append((endpoint, message)))
            app = FastAPI()

            @app.middleware("http")
            async def identity(request, call_next):
                token = request.headers.get("authorization", "")
                if token in ("Bearer fixture-alice", "Bearer fixture-bob"):
                    request.state.session = SimpleNamespace(user_id=token.split("-")[-1], provider="fixture", issuer="")
                return await call_next(request)

            async def native(*args, **kwargs):
                raise AssertionError("Push must not call global native conversation or subscription routes")

            app.include_router(api.make_router(store, native, push=service), prefix="/plugin")
            with TestClient(app) as client:
                alice = {"Authorization": "Bearer fixture-alice"}
                bob = {"Authorization": "Bearer fixture-bob"}
                key = client.get("/plugin/push/key", headers=alice)
                self.assertEqual(200, key.status_code, "The channel plugin must own its push key and recipients")
                self.assertEqual(401, client.get("/plugin/push/key").status_code)
                self.assertNotEqual(key.json()["key"], client.get("/plugin/push/key", headers=bob).json()["key"])
                body = {"endpoint": "https://push.example/alice", "p256dh": "fixture-key", "auth": "fixture-auth", "device": "a"}
                self.assertEqual(200, client.post("/plugin/push/subscriptions", json=body, headers=alice).status_code)
                self.assertEqual(422, client.post("/plugin/push/subscriptions", json={**body, "owner": "bob"}, headers=alice).status_code)
                self.assertEqual(0, client.post("/plugin/push/test", headers=bob).json()["sent"])
                self.assertFalse(client.request("DELETE", "/plugin/push/subscriptions", json={"endpoint": body["endpoint"]}, headers=bob).json()["removed"])
                self.assertEqual(1, client.post("/plugin/push/test", headers=alice).json()["sent"])
                self.assertEqual([body["endpoint"]], [entry[0] for entry in sent])
                self.assertEqual(key.json()["channel_owner"], sent[0][1]["channel_owner"])

    def test_detail_search_and_mutations_share_channel_membership_and_readable_projection(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            store = registry.Registry(Path(directory))
            store.claim(json.dumps(["fixture", "", "alice"], separators=(",", ":")), "owned")
            calls = []

            async def native(request, method, path, **kwargs):
                calls.append((method, path, kwargs))
                if path.endswith("/messages"):
                    return {"session_id": "owned", "messages": [{"id": 1, "role": "tool", "content": "PRIVATE"}, {"id": 2, "role": "assistant", "content": "Visible answer"}], "pagination": {"total": 2}}
                if path.endswith("mutation-snapshot"):
                    return {"runtime_revision": 7, "runtime_generation": 2}
                return {"id": "owned", "title": "Owned", "source": "tool", "preview": "Visible", "raw_tool_result": "PRIVATE", "ok": True}

            app = FastAPI()

            @app.middleware("http")
            async def identity(request, call_next):
                request.state.session = SimpleNamespace(user_id="alice", provider="fixture", issuer="")
                return await call_next(request)

            app.include_router(api.make_router(store, native), prefix="/plugin")
            with TestClient(app, headers={"Authorization": "Bearer fixture-alice"}) as client:
                detail = client.get("/plugin/api/sessions/owned")
                self.assertEqual(200, detail.status_code, "Owned session detail needs the same channel route as history")
                self.assertNotIn("PRIVATE", detail.text)
                self.assertEqual("android", detail.json()["source"])
                self.assertEqual([], client.get("/plugin/api/sessions/search?q=PRIVATE").json()["results"])
                results = client.get("/plugin/api/sessions/search?q=Visible").json()["results"]
                self.assertEqual(["owned"], [r["session_id"] for r in results])
                self.assertEqual(7, client.get("/plugin/api/sessions/owned/mutation-snapshot").json()["runtime_revision"])
                self.assertEqual(200, client.patch("/plugin/api/sessions/owned", json={"title": "Changed", "request_id": "r1", "expected_revision": 7}).status_code)
                self.assertEqual("r1", calls[-1][2]["json"]["request_id"])
                self.assertEqual(200, client.delete("/plugin/api/sessions/owned?request_id=r2&expected_revision=7").status_code)
                before = len(calls)
                for method, path in [("GET", "foreign"), ("GET", "foreign/mutation-snapshot"), ("PATCH", "foreign"), ("DELETE", "foreign")]:
                    self.assertEqual(404, client.request(method, "/plugin/api/sessions/" + path).status_code)
                self.assertEqual(before, len(calls))

    def test_chat_list_omits_empty_drafts_when_the_client_asks_for_chats_with_messages(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            store = registry.Registry(Path(directory))
            owner = json.dumps(["fixture", "", "alice"], separators=(",", ":"))
            rows = {
                "used": {"id": "used", "title": "Owned", "preview": "", "message_count": 12, "last_active": 5, "archived": False},
                "draft": {"id": "draft", "title": None, "preview": None, "message_count": 0, "last_active": 9, "archived": False},
            }
            for session_id in rows:
                store.claim(owner, session_id)

            async def native(request, method, path, **kwargs):
                return dict(rows[path.rsplit("/", 1)[-1]])

            app = FastAPI()

            @app.middleware("http")
            async def signed_in(request, call_next):
                request.state.session = SimpleNamespace(user_id="alice", provider="fixture", issuer="")
                return await call_next(request)

            app.include_router(api.make_router(store, native), prefix="/plugin")
            with TestClient(app, headers={"Authorization": "Bearer fixture-alice"}) as client:
                # What ChatApi.list() sends for the chats list; min_messages=1 hides the drafts.
                listed = client.get("/plugin/api/sessions", params={"limit": 50, "offset": 0, "order": "recent",
                                                                   "archived": "exclude", "min_messages": 1})
                self.assertEqual(200, listed.status_code, listed.text)
                self.assertEqual(["used"], [row["id"] for row in listed.json()["sessions"]],
                                 "A phone that asks for chats with messages must not be shown an empty draft")
                self.assertEqual(1, listed.json()["total"], "The total must count only the rows the filter kept")
                everything = client.get("/plugin/api/sessions").json()
                self.assertEqual(["draft", "used"], [row["id"] for row in everything["sessions"]],
                                 "Without min_messages every claimed conversation stays listed")
                self.assertEqual(2, everything["total"])

    def test_authenticated_http_flow_never_reads_or_exposes_foreign_conversations(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            store = registry.Registry(Path(directory))
            owner = json.dumps(["fixture", "", "alice"], separators=(",", ":"))
            store.claim(owner, "own-session")
            calls = []

            async def native(request, method, path, **kwargs):
                calls.append((method, path))
                if path == "api/sessions/own-session":
                    return {"id": "own-session", "title": "Owned", "source": "android", "message_count": 2}
                if path == "api/sessions/own-session/messages":
                    return {"session_id": "own-session", "profile": "default", "pagination": {"returned": 1},
                            "messages": [{"id": 1, "role": "tool", "content": "PRIVATE_RESULT"}]}
                return {"sessions": [{"id": "foreign-session", "title": "PRIVATE_FOREIGN"}], "total": 1}

            app = FastAPI()

            @app.middleware("http")
            async def verified_fixture_identity(request, call_next):
                if request.headers.get("authorization") == "Bearer fixture-alice":
                    request.state.session = SimpleNamespace(user_id="alice", provider="fixture", issuer="")
                return await call_next(request)

            app.include_router(api.make_router(store, native), prefix="/plugin")
            with TestClient(app) as client:
                denied = client.get("/plugin/api/sessions")
                self.assertEqual(401, denied.status_code, "The plugin must refuse without a verified principal")
                self.assertEqual([], calls)
                headers = {"Authorization": "Bearer fixture-alice"}
                listing = client.get("/plugin/api/sessions", headers=headers)
                self.assertEqual(200, listing.status_code)
                self.assertEqual(["own-session"], [r["id"] for r in listing.json()["sessions"]])
                before = len(calls)
                foreign = client.get("/plugin/api/sessions/foreign-session/messages", headers=headers)
                self.assertEqual(404, foreign.status_code)
                self.assertEqual(before, len(calls), "Foreign id must never reach the native history route")
                history = client.get("/plugin/api/sessions/own-session/messages", headers=headers)
                self.assertEqual(200, history.status_code)
                self.assertNotIn("PRIVATE_RESULT", history.text)
                self.assertNotIn("PRIVATE_FOREIGN", listing.text)
                self.assertEqual("android-minimal-v1", history.json()["projection"])


if __name__ == "__main__":
    unittest.main()
