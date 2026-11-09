"""Conversation-scoped boundary in front of a native JSON-RPC client."""
from . import projection
import uuid
from typing import Any

_GLOBAL = {"client.capabilities", "gateway.ping", "ping", "connectors.list", "connectors.catalog",
           "connectors.accounts", "connectors.tools", "connectors.connect", "connectors.accounts.remove"}
_SCOPED = {"session.create", "session.resume", "session.info", "session.interrupt", "session.close", "session.detach",
           "session.undo", "session.title", "session.delete", "session.archive", "session.events.since", "prompt.submit",
           "config.set", "file.attach", "image.attach_bytes", "connection.respond", "connectors.operation.status", "connectors.operation.wake"}
_OPERATIONS = {"connection.respond", "connectors.operation.status", "connectors.operation.wake"}


class Bridge:
    def __init__(self, native, registry, owner):
        self.native, self.registry, self.owner = native, registry, owner
        self.bound = {}
        self.requests = {}
        self.generations = {}
        self.subscriptions = {}
        self.operations = {}
        self.model_confirmations = {}

    async def model(self, params):
        value = params.get("value")
        if not isinstance(value, str) or params.get("scope") == "global":
            raise ValueError("A session model selection is required")
        model, separator, provider = value.rpartition(" --provider ")
        if not separator or not model or not provider:
            raise ValueError("A model and provider are required")
        live = params["session_id"]
        key = (live, model, provider)
        confirmed = params.get("confirm_expensive_model") is True
        if confirmed:
            wire = self.model_confirmations.pop(key, None)
            if wire is None:
                raise PermissionError("This model confirmation was not presented on this connection")
        else:
            snapshot = await self.native.rpc("session.resume", session_id=live, omit_messages=True)
            if type(snapshot.get("revision")) is not int or type(snapshot.get("execution_generation")) is not int:
                raise ValueError("Rejoin before changing an unknown session revision")
            if isinstance(snapshot.get("subscription_id"), str):
                self.subscriptions[live] = snapshot["subscription_id"]
            self.generations[live] = snapshot["execution_generation"]
            wire = dict(session_id=live, request_id=uuid.uuid4().hex, operation="model",
                        expected_revision=snapshot["revision"], expected_generation=snapshot["execution_generation"],
                        payload={"model": model, "provider": provider})
        result = await self.native.rpc("session.mutate", **wire)
        if result.get("status") == "confirmation_required":
            if confirmed or not isinstance(result.get("confirm"), str):
                raise ValueError("Model selection changed; select the model again")
            self.model_confirmations = {key: item for key, item in self.model_confirmations.items() if key[0] != live}
            self.model_confirmations[key] = {**wire, "request_id": uuid.uuid4().hex,
                                              "payload": {"model": model, "provider": provider, "confirm": result["confirm"]}}
            return {"confirm_required": True, "confirm_message": result.get("confirm_message", "Confirm this model")}
        return {key: result[key] for key in ("model", "provider", "deferred", "warning") if key in result}

    def forward(self, frame):
        params = frame.get("params") or {}
        if params.get("type") != "gateway.ready" and params.get("session_id") not in self.bound:
            return None
        projected = projection.event(frame)
        if type(params.get("execution_generation")) is int:
            self.generations[params["session_id"]] = params["execution_generation"]
        if params.get("type") == "request.cancel":
            self.requests.pop((params.get("payload") or {}).get("id"), None)
        if params.get("type") in {"connection.request", "connection.update"}:
            payload = params.get("payload") or {}
            owner = payload.get("owner")
            if isinstance(payload.get("op_id"), str) and isinstance(owner, dict) and owner == {"type": "session", "session_id": params.get("session_id")}:
                self.operations[payload["op_id"]] = dict(owner)
        if projected is not None and frame.get("id") and frame.get("method") != "event":
            self.requests[frame["id"]] = params["session_id"]
        return projected

    async def respond(self, frame):
        if frame.get("id") not in self.requests or "method" in frame:
            raise PermissionError("Request was not presented on this connection")
        await self.native.respond(frame)
        self.requests.pop(frame["id"], None)

    async def request(self, method, params):
        if method not in _GLOBAL | _SCOPED:
            raise PermissionError("RPC is not part of the Android channel interface")
        params = dict(params)
        if params.get("profile"):
            raise PermissionError("Use the channel's own profile listener")
        if method == "session.resume" and set(params) - {"session_id", "omit_messages", "inline_images", "close_on_disconnect"}:
            raise PermissionError("Resume accepts only an owned conversation identity")
        if params.get("session_key") and not self.registry.owns(self.owner, params["session_key"]):
            raise PermissionError("Conversation is not owned by this channel")
        operation_owner = params.get("owner")
        if isinstance(operation_owner, dict) and operation_owner.get("type") == "session" and operation_owner.get("session_id") not in self.bound:
            raise PermissionError("Operation is not owned by this channel")
        create_title = params.get("title") if method == "session.create" and getattr(self.native, "canonical", False) else None
        wire_method = method
        if method == "session.resume":
            if not self.registry.owns(self.owner, params.get("session_id")):
                raise PermissionError("Conversation is not owned by this channel")
        elif method in _OPERATIONS:
            if operation_owner is None or self.operations.get(params.get("op_id")) != operation_owner:
                raise PermissionError("Operation was not presented on this connection")
        elif method != "session.create" and method not in _GLOBAL:
            if params.get("session_id") not in self.bound:
                raise PermissionError("Conversation is not joined on this connection")
        if getattr(self.native, "canonical", False):
            if method == "config.set" and params.get("key") == "model":
                return await self.model(params)
            if method == "session.undo":
                live = params["session_id"]
                snapshot = await self.native.rpc("session.resume", session_id=live)
                users = [row for row in snapshot.get("messages", []) if row.get("role") == "user" and
                         projection.is_readable(row) and not row.get("archived") and type(row.get("row_id")) is int and row["row_id"] > 0]
                if not users or type(snapshot.get("revision")) is not int or type(snapshot.get("execution_generation")) is not int:
                    raise ValueError("No verified human turn to rewind; refresh the conversation")
                if isinstance(snapshot.get("subscription_id"), str):
                    self.subscriptions[live] = snapshot["subscription_id"]
                self.generations[live] = snapshot["execution_generation"]
                await self.native.rpc("session.mutate", session_id=live, request_id=uuid.uuid4().hex,
                                      expected_revision=snapshot["revision"], expected_generation=snapshot["execution_generation"],
                                      operation="rewind", payload={"target_message_id": users[-1]["row_id"]})
                return {"ok": True}
            if method == "session.create":
                params = {key: value for key, value in params.items() if key in ("cwd", "model", "provider")}
                params.update(source="tool", request_id=uuid.uuid4().hex)
            elif method == "prompt.submit":
                params.setdefault("submission_id", uuid.uuid4().hex)
                params["clarify_batch"] = True
            elif method == "session.resume":
                params = {key: value for key, value in params.items() if key in ("session_id", "omit_messages")}
            elif method == "session.interrupt" and "execution_generation" not in params:
                generation = self.generations.get(params["session_id"])
                if type(generation) is not int:
                    raise ValueError("Rejoin before interrupting an unknown execution")
                params["execution_generation"] = generation
            elif method == "session.close":
                wire_method = "session.detach"
                params.update(subscription_id=self.subscriptions[params["session_id"]], end_reason="android_viewer_closed")
            elif method == "gateway.ping":
                wire_method = "ping"
        result: dict[str, Any] = await self.native.rpc(wire_method, **params)
        if method == "connectors.connect" and isinstance(operation_owner, dict) and isinstance(result.get("op_id"), str):
            self.operations[result["op_id"]] = dict(operation_owner)
        if method in ("session.create", "session.resume"):
            stored = result.get("stored_session_id") or result.get("session_key")
            live = result.get("session_id")
            if not isinstance(stored, str) or not stored or not isinstance(live, str) or not live:
                raise ValueError("Native gateway did not establish conversation identity")
            if method == "session.resume":
                logical = self.registry.logical(params["session_id"])
                self.registry.alias(logical, stored)  # Only an authorized native resume can confirm this alias.
                stored = logical
            self.registry.claim(self.owner, stored)
            self.bound[live] = stored
            if type(result.get("execution_generation")) is int:
                self.generations[live] = result["execution_generation"]
            if isinstance(result.get("subscription_id"), str):
                self.subscriptions[live] = result["subscription_id"]
            if create_title is not None:
                # Canonical create(title=...) is resolve-or-create, not a label.
                # Label only the freshly server-claimed identity, never title-resolve
                # some unrelated native conversation into this channel.
                if type(result.get("revision")) is not int:
                    raise ValueError("Native creation did not establish a mutation revision")
                await self.native.rpc("session.mutate", session_id=live, request_id=uuid.uuid4().hex,
                                      expected_revision=result["revision"], operation="rename", payload={"title": create_title})
            result = dict(result, session_key=stored, stored_session_id=stored)
            pending = result.get("pending_connection")
            if isinstance(pending, dict):
                self.forward({"method": "event", "params": {"type": "connection.request", "session_id": live, "payload": pending}})
            for request in result.get("open_requests") or []:
                if (request.get("params") or {}).get("session_id") == live and projection.event(request) is not None:
                    self.requests[request["id"]] = live
        if method == "session.close":
            live = params["session_id"]
            self.bound.pop(live, None)
            self.generations.pop(live, None)
            self.subscriptions.pop(live, None)
            self.requests = {key: sid for key, sid in self.requests.items() if sid != live}
            self.operations = {key: owner for key, owner in self.operations.items() if owner.get("session_id") != live}
        return projection.result(method, result)
