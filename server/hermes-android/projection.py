"""Explicit client-bound fields; never mutate canonical gateway frames."""
from copy import deepcopy
from typing import Any


def result(method, value):
    if method in ("session.create", "session.resume", "session.activate"):
        out = {key: deepcopy(value[key]) for key in
               ("session_id", "stored_session_id", "session_key", "message_count", "messages_omitted",
                "running", "status", "started_at", "turn_started_at", "replay_epoch", "last_sequence",
                "revision", "execution_generation", "subscription_id", "authority_epoch") if key in value}
        out["messages"] = [{**{key: deepcopy(row[key]) for key in
                            ("role", "row_id", "id", "timestamp", "display_kind") if key in row},
                            "text": readable(row.get("text", ""))}
                           for row in value.get("messages", []) if is_readable(row)]
        out["info"] = {key: deepcopy(value["info"][key]) for key in
                       ("model", "provider", "profile_name", "title", "running", "started_at", "last_active", "source", "reasoning_effort")
                       if key in (value.get("info") or {})}
        if isinstance(value.get("inflight"), dict):
            out["inflight"] = {key: deepcopy(value["inflight"][key]) for key in
                               ("user", "assistant", "streaming", "error", "status", "recoverable", "error_surface", "corrections", "correction_offsets")
                               if key in value["inflight"]}
        if value.get("open_requests"):
            out["open_requests"] = []
            for entry in value["open_requests"]:
                projected = event(entry)
                if projected is not None:
                    projected.pop("jsonrpc", None)
                    if entry.get("method") == "clarify" and isinstance(entry.get("answers"), dict):
                        projected["answers"] = deepcopy(entry["answers"])
                    out["open_requests"].append(projected)
        if isinstance(value.get("pending_connection"), dict):
            out["pending_connection"] = connection(value["pending_connection"])
        return out
    if method == "session.events.since":
        out = {key: deepcopy(value[key]) for key in
               ("latest_seq", "last_sequence", "epoch", "replay_epoch", "truncated", "snapshot_required") if key in value}
        out["events"] = [projected["params"] for row in value.get("events", [])
                         if (projected := event({"method": "event", "params": row})) is not None]
        out["count"] = len(out["events"])
        if value.get("open_requests"):
            out["open_requests"] = result("session.resume", {"open_requests": value["open_requests"]})["open_requests"]
        return out
    if method == "prompt.submit":
        return {key: deepcopy(value[key]) for key in
                ("status", "admission_id", "ref", "sequence", "outcome", "authority_epoch", "execution_generation",
                 "user_row_id", "session_id", "stamp") if key in value}
    if method == "session.info":
        return {key: deepcopy(value[key]) for key in
                ("model", "provider", "profile_name", "title", "running", "status", "execution_generation", "message_count",
                 "session_id", "stored_session_id", "session_key", "replay_epoch", "last_sequence") if key in value}
    if method in {"config.set", "connection.respond", "connectors.list", "connectors.catalog", "connectors.accounts", "connectors.tools",
                  "connectors.connect", "connectors.accounts.remove", "connectors.operation.status", "connectors.operation.wake"}:
        return deepcopy(value)  # Explicit user-facing account/config operations, not model tool calls.
    if method in {"client.capabilities", "gateway.ping", "ping", "session.interrupt", "session.close", "session.detach",
                  "session.undo", "session.title", "session.delete", "session.archive", "file.attach", "image.attach_bytes"}:
        return {key: deepcopy(value[key]) for key in
                ("ok", "status", "pong", "heartbeat", "server_requests", "session_id", "ref_text", "file", "name",
                 "mime_type", "size", "path", "image", "message_count", "removed", "title", "archived") if key in value}
    return {}


def is_readable(row):
    role, kind = row.get("role"), row.get("display_kind")
    if role not in ("user", "assistant") or kind == "hidden" or row.get("_compressed_summary"):
        return False
    if role == "user":
        if kind not in (None, "steer"):
            return False
        text = row.get("display_content", row.get("text", row.get("content", "")))
        if kind is None and isinstance(text, str) and text.startswith(("[IMPORTANT: Background process ", "[ASYNC DELEGATION BATCH COMPLETE", "[System: ")):
            return False
    return True


def readable(content):
    if isinstance(content, str):
        return content
    blocks = []
    for block in content if isinstance(content, list) else []:
        if not isinstance(block, dict):
            continue
        if block.get("type") in ("text", "input_text", "output_text") and isinstance(block.get("text"), str):
            blocks.append({"type": block["type"], "text": block["text"]})
        elif block.get("type") == "image_url" and isinstance(block.get("image_url"), dict):
            image = {key: deepcopy(block["image_url"][key]) for key in ("url", "detail") if key in block["image_url"]}
            blocks.append({"type": "image_url", "image_url": image})
    return blocks


def history(page):
    messages, evidence = [], []
    for source in page.get("messages") or []:
        row = {key: deepcopy(source[key]) for key in
               ("id", "role", "timestamp", "display_kind", "_compressed_summary") if key in source}
        visible = is_readable(source)
        row["content"] = readable(source.get("content", "")) if visible else ""
        if visible and "display_content" in source:
            row["display_content"] = readable(source["display_content"])
        proof = deepcopy(row)
        if source.get("tool_calls"):
            proof["tool_calls"] = [{"id": call["id"]} for call in source["tool_calls"] if isinstance(call.get("id"), str)]
        if isinstance(source.get("tool_call_id"), str):
            proof["tool_call_id"] = source["tool_call_id"]
        evidence.append(proof)
        if not visible:
            row["display_kind"] = "hidden"
        messages.append(row)
    return {**{key: deepcopy(page[key]) for key in ("session_id", "profile", "pagination") if key in page},
            "messages": messages, "receipt_rows": evidence, "projection": "android-minimal-v1"}

_FIELDS = {
    "error": ("message",),
    "session.info": ("model", "provider", "profile_name", "title", "reasoning_effort"),
    "gateway.ready": ("heartbeat", "replay_epoch", "session_authority"),
    "status.update": ("text",),
    "message.start": (),
    "message.delta": ("text",),
    "message.complete": ("status", "text", "error", "partial", "failure_reason", "persisted_turn"),
    "request.cancel": ("id", "method", "reason"),
    "connection.request": (),
    "connection.update": (),
}
_REQUEST_FIELDS = {
    "approval": ("session_id", "command", "description", "pattern_key", "pattern_keys", "tool_name", "choices", "allow_permanent", "allow_session", "smart_denied"),
    "clarify": ("session_id", "questions"),
    "secret": ("session_id", "prompt", "env_var"),
    "sudo": ("session_id", "command"),
}


def event(frame):
    method = frame.get("method")
    params = frame.get("params") or {}
    if method in _REQUEST_FIELDS and isinstance(frame.get("id"), str):
        return {"jsonrpc": "2.0", "id": frame["id"], "method": method,
                "params": {key: deepcopy(params[key]) for key in _REQUEST_FIELDS[method] if key in params}}
    if method != "event":
        return None
    kind = params.get("type")
    if kind not in _FIELDS:
        return None
    payload = params.get("payload") or {}
    envelope = {key: deepcopy(params[key]) for key in ("type", "session_id", "seq", "replay_epoch", "admission_id", "execution_generation") if key in params}
    body: dict[str, Any] = {key: deepcopy(payload[key]) for key in _FIELDS[kind] if key in payload}
    if kind == "status.update":
        body = {"text": "Working"}
    if kind in ("connection.request", "connection.update"):
        body = connection(payload)
    if isinstance(body.get("persisted_turn"), dict):
        body["persisted_turn"] = {key: value for key, value in body["persisted_turn"].items()
                                  if key in ("complete", "user_row_id", "final_assistant_row_id", "row_ids", "user_row_ids")}
    envelope["payload"] = body
    return {"jsonrpc": "2.0", "method": "event", "params": envelope}


def connection(payload):
    out = {key: deepcopy(payload[key]) for key in
           ("owner", "op_id", "seq", "deadline_at", "timeout_seconds", "tool_call_id", "display",
            "settled", "settled_at", "settled_by", "target", "from", "to", "actor", "detail") if key in payload}
    fields = ("name", "kind", "action", "state", "resolved", "detail", "instructions", "discovery_error",
              "connect_url", "connection_id", "alias", "attempt", "required_env", "launch", "tools", "hint",
              "display", "description", "tier", "platforms", "repo", "ref", "scan", "approved", "error",
              "server_errors", "app_state", "version")
    out["targets"] = [{key: deepcopy(target[key]) for key in fields if key in target}
                      for target in payload.get("targets", [])]
    return out
