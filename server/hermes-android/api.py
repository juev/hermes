"""Authenticated channel HTTP routes, mounted by the documented dashboard SDK."""
import json
import asyncio

from fastapi import APIRouter, HTTPException, Query, Request, WebSocket
from pydantic import BaseModel, ConfigDict, Field

from . import projection
from .tickets import Tickets
from .socket import relay
from .push import PushService, CHANNEL, identity


class Endpoint(BaseModel):
    model_config = ConfigDict(extra="forbid")
    endpoint: str = Field(min_length=1, max_length=4096)


class Subscription(Endpoint):
    p256dh: str = Field(min_length=1, max_length=1024)
    auth: str = Field(min_length=1, max_length=256)
    name: str = Field(default="", max_length=128)
    device: str = Field(default="", max_length=128)


def summary(row, session_id):
    fields = ("id", "title", "preview", "model", "started_at", "last_active", "last_activity_at", "message_count",
              "pinned", "archived", "is_active", "unread", "last_read_at", "input_tokens", "output_tokens",
              "cache_read_tokens", "cache_write_tokens", "actual_cost_usd", "estimated_cost_usd", "cost_status")
    return {**{key: row[key] for key in fields if key in row}, "id": session_id, "source": "android", "created_source": "android"}


def principal(request):
    session = getattr(request.state, "session", None)
    user = getattr(session, "user_id", None)
    provider = getattr(session, "provider", None)
    if not isinstance(user, str) or not user or not isinstance(provider, str) or not provider:
        raise HTTPException(status_code=401, detail="Verified native sign-in required")
    if not request.headers.get("authorization", "").startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Native bearer credential required")
    # User backend plugins are served by their own dashboard profile. Never let a
    # selected profile silently turn an internal request into a launch-profile read.
    if request.query_params.get("profile"):
        raise HTTPException(status_code=400, detail="Use the plugin's own profile listener")
    return json.dumps([provider, getattr(session, "issuer", ""), user], separators=(",", ":"))


def make_router(store, native_http, native_connect=None, push=None):
    router = APIRouter()
    tickets = Tickets()
    pusher = push if push is not None else PushService(store)

    @router.get("/push/key")
    async def push_key(request: Request):
        owner = principal(request)
        key = pusher.key(owner, create=True)
        assert key is not None
        return {"key": key.public_key, "channel": CHANNEL, "channel_owner": identity(owner)}

    @router.post("/push/subscriptions")
    async def subscribe(request: Request, body: Subscription):
        owner = principal(request)
        if not body.endpoint.startswith("https://"):
            raise HTTPException(status_code=400, detail="Push endpoints must use HTTPS")
        pusher.subscribe(owner, **body.model_dump())
        return {"ok": True}

    @router.delete("/push/subscriptions")
    async def unsubscribe(request: Request, body: Endpoint):
        return {"ok": True, "removed": pusher.unsubscribe(principal(request), body.endpoint)}

    @router.post("/push/test")
    async def test_push(request: Request):
        owner = principal(request)
        sent = await asyncio.to_thread(pusher._send, owner, {"type": "test", "channel": CHANNEL,
                                                            "channel_owner": identity(owner), "preview": "Android channel push works"})
        return {"ok": True, "sent": sent}

    @router.post("/ws-ticket")
    async def ticket(request: Request):
        owner = principal(request)
        upstream = await native_http(request, "POST", "api/auth/ws-ticket")
        entry = {"owner": owner, "native_ticket": upstream["ticket"], "server": request.scope.get("server")}
        try:
            token = tickets.issue(entry)
        except RuntimeError as exc:
            raise HTTPException(status_code=429, detail=str(exc)) from None
        return {"ticket": token, "ttl_seconds": 20, "channel_version": 1}

    @router.websocket("/ws")
    async def socket(ws: WebSocket):
        entry = tickets.take(ws.query_params.get("ticket"))
        if entry is None or ws.query_params.get("profile"):
            await ws.close(code=4401)
            return
        connector = native_connect
        if connector is None:
            from .upstream import connection
            connector = connection
        await ws.accept()
        try:
            async with connector(entry) as native:
                await relay(ws, native, store, entry["owner"])
        except Exception:
            # Ticket URLs, request bodies and native diagnostics are never logged.
            try:
                await ws.close(code=1011, reason="Native connection unavailable; reconnect")
            except RuntimeError:
                pass

    def owned(request, session_id):
        owner = principal(request)
        if not store.owns(owner, session_id):
            raise HTTPException(status_code=404, detail="Conversation not found")
        return owner

    def history(owner, session_id, page):
        logical = store.logical(session_id)
        returned = page.get("session_id")
        if not store.owns(owner, returned) or store.logical(returned) != logical:
            raise HTTPException(status_code=409, detail="Native conversation changed; rejoin before reading")
        return projection.history({**page, "session_id": logical})

    @router.get("/api/sessions")
    async def sessions(request: Request, limit: int = Query(50, ge=0, le=500), offset: int = Query(0, ge=0),
                       min_messages: int = Query(0, ge=0)):
        owner = principal(request)
        rows = []
        for session_id in store.sessions(owner):
            try:
                row = await native_http(request, "GET", "api/sessions/" + session_id)
            except HTTPException as exc:
                if exc.status_code == 404:
                    continue  # A newly created native session may not be persisted yet.
                raise
            row = summary(row, store.logical(session_id))
            # A chat whose session the client created before its first message holds no rows yet;
            # the phone asks with min_messages=1 so its list shows only chats it can read.
            if int(row.get("message_count") or 0) < min_messages:
                continue
            archive = request.query_params.get("archived")
            if archive == "exclude" and row.get("archived") or archive == "only" and not row.get("archived"):
                continue
            sources = request.query_params.get("sources")
            excluded = request.query_params.get("exclude_sources", "").split(",")
            if sources and "android" not in sources.split(",") or "android" in excluded:
                continue
            rows.append(row)
        rows.sort(key=lambda row: float(row.get("last_active") or row.get("started_at") or 0), reverse=True)
        return {"sessions": rows[offset:offset + limit], "total": len(rows), "limit": limit, "offset": offset}

    @router.get("/api/sessions/search")
    async def search(request: Request, q: str = "", limit: int = Query(50, ge=1, le=200)):
        owner = principal(request)
        hits = []
        if not q.strip():
            return {"results": hits, "total": 0}
        for session_id in store.sessions(owner):
            row = summary(await native_http(request, "GET", "api/sessions/" + session_id), store.logical(session_id))
            offset = 0
            while len(hits) < limit:
                page = await native_http(request, "GET", f"api/sessions/{session_id}/messages",
                                         params={"limit": 500, "offset": offset, "order": "oldest", "inline_images": "false"})
                raw = page.get("messages") or []
                for message in history(owner, session_id, page)["messages"]:
                    if message.get("role") not in ("user", "assistant") or message.get("display_kind") == "hidden":
                        continue
                    content = message.get("display_content", message["content"])
                    text = content if isinstance(content, str) else "\n".join(block.get("text", "") for block in content)
                    index = text.casefold().find(q.casefold())
                    if index >= 0 and len(hits) < limit:
                        hits.append({**row, "session_id": session_id, "role": message["role"], "snippet": text[max(0, index - 50):index + 150]})
                offset += len(raw)
                total = (page.get("pagination") or {}).get("total")
                if len(raw) < 500 or isinstance(total, int) and offset >= total:
                    break
            if len(hits) >= limit:
                break
        return {"results": hits, "total": len(hits)}

    @router.get("/api/sessions/{session_id}")
    async def detail(request: Request, session_id: str):
        owned(request, session_id)
        return summary(await native_http(request, "GET", "api/sessions/" + session_id), store.logical(session_id))

    @router.get("/api/sessions/{session_id}/mutation-snapshot")
    async def mutation_snapshot(request: Request, session_id: str):
        owned(request, session_id)
        value = await native_http(request, "GET", f"api/sessions/{session_id}/mutation-snapshot")
        return {key: value[key] for key in ("runtime_revision", "runtime_generation") if key in value}

    @router.patch("/api/sessions/{session_id}")
    async def mutate(request: Request, session_id: str):
        owned(request, session_id)
        await native_http(request, "PATCH", "api/sessions/" + session_id, json=await request.json())
        return {"ok": True}

    @router.delete("/api/sessions/{session_id}")
    async def delete(request: Request, session_id: str):
        owned(request, session_id)
        params = {key: value for key, value in request.query_params.items()
                  if key in ("request_id", "expected_revision", "expected_generation")}
        await native_http(request, "DELETE", "api/sessions/" + session_id, params=params)
        return {"ok": True}

    @router.get("/api/sessions/{session_id}/messages")
    async def messages(request: Request, session_id: str):
        owner = owned(request, session_id)
        params = {key: value for key, value in request.query_params.items()
                  if key in ("limit", "offset", "order", "include_compacted")}
        params["inline_images"] = "false"
        page = await native_http(request, "GET", f"api/sessions/{session_id}/messages", params=params)
        return history(owner, session_id, page)

    return router
