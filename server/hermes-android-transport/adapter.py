"""Android channel transport.

The phone is a chat client of the gateway, not a second API surface. A
conversation arrives as an inbound ``MessageEvent`` (one gateway session per
``chat_id``) and answers leave through ``send``/``send_draft``/``send_clarify``/
``_send_exec_approval_prompt`` — the same seams every chat platform uses. That is
why reasoning and tool payloads never reach the phone: the platform layer never
carries them, so no projection is needed.
"""

from __future__ import annotations

import asyncio
import hmac
import json
import logging
import time
import uuid
from http import HTTPStatus
from typing import Any, Optional
from urllib.parse import parse_qs, urlsplit

from gateway.config import Platform, PlatformConfig
from gateway.platforms.base import BasePlatformAdapter, SendResult
from gateway.platforms.event import MessageEvent, MessageType

from .storage import ChannelStore

logger = logging.getLogger(__name__)

CHANNEL_PATH = "/channel"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8799
APPROVAL_CHOICES = frozenset({"once", "session", "always", "deny"})


def _setting(config, key: str, env: str, default: Any = None) -> Any:
    extra = getattr(config, "extra", None) or {}
    value = extra.get(key)
    if value not in (None, ""):
        return value
    try:
        from gateway.platforms._shared import get_scoped_secret

        value = get_scoped_secret(env)
    except Exception:  # pragma: no cover - no profile scope outside a gateway
        import os

        value = os.environ.get(env)
    return value if value not in (None, "") else default


def _bearer(header: Optional[str]) -> str:
    if not header:
        return ""
    scheme, _, token = header.partition(" ")
    return token.strip() if scheme.lower() == "bearer" else ""


class _Client:
    """One phone connection; carries the chat ids it subscribed to."""

    def __init__(self, websocket) -> None:
        self.ws = websocket
        self.chats: set[str] = set()
        self._lock = asyncio.Lock()
        self._closed = False

    async def send(self, payload: dict) -> bool:
        if self._closed:
            return False
        body = json.dumps(payload, ensure_ascii=False)
        async with self._lock:
            try:
                await self.ws.send(body)
            except Exception:
                self._closed = True
                return False
        return True

    async def close(self) -> None:
        self._closed = True
        try:
            await self.ws.close()
        except Exception:
            pass


class AndroidChannelAdapter(BasePlatformAdapter):
    """Gateway platform for the Android client over an authenticated WebSocket."""

    MAX_MESSAGE_LENGTH = 32_768
    supports_code_blocks = True

    @property
    def authorization_is_upstream(self) -> bool:
        """The handshake authenticates the phone: a wrong bearer answers 401 before any frame.

        Authorization is delegated to the channel token, not absent — the same declaration A2A
        makes. Without it the gateway's no-allowlist ladder would reject every message.
        """
        return True

    def __init__(self, config: PlatformConfig, *, platform: Optional[Platform] = None, root=None) -> None:
        super().__init__(config, platform or Platform("android"))
        self._host = str(_setting(config, "host", "ANDROID_CHANNEL_HOST", DEFAULT_HOST))
        self._port = int(_setting(config, "port", "ANDROID_CHANNEL_PORT", DEFAULT_PORT))
        self._token = str(_setting(config, "token", "ANDROID_CHANNEL_TOKEN", "") or "")
        self._principal = str(_setting(config, "principal", "ANDROID_CHANNEL_PRINCIPAL", "android") or "android")
        self._store = ChannelStore(root)
        self._server = None
        self._clients: set[_Client] = set()
        self._approvals: dict[str, str] = {}  # request id -> session key

    # ── lifecycle ────────────────────────────────────────────────────────────

    @property
    def bound_port(self) -> int:
        try:
            sockets = list(self._server.sockets)  # type: ignore[union-attr]
            return int(sockets[0].getsockname()[1])
        except Exception:
            return 0

    async def connect(self, **_kwargs) -> bool:
        if not self._token:
            logger.error("[android] refusing to start: ANDROID_CHANNEL_TOKEN is not configured")
            return False
        from websockets.asyncio.server import serve

        self._server = await serve(
            self._on_connection,
            self._host,
            self._port,
            process_request=self._authorize,
            max_size=8 * 1024 * 1024,
            ping_interval=30,
            ping_timeout=30,
        )
        self._running = True
        logger.info("[android] channel listening on ws://%s:%s%s", self._host, self.bound_port, CHANNEL_PATH)
        return True

    async def disconnect(self) -> None:
        self._running = False
        server, self._server = self._server, None
        if server is not None:
            server.close()
            try:
                await server.wait_closed()
            except Exception:
                logger.debug("[android] server close failed", exc_info=True)
        for client in list(self._clients):
            await client.close()
        self._clients.clear()

    async def _authorize(self, connection, request):
        """Reject unauthenticated handshakes before any frame is accepted."""
        path = urlsplit(getattr(request, "path", "") or "")
        if path.path.rstrip("/") != CHANNEL_PATH:
            return connection.respond(HTTPStatus.NOT_FOUND, "not found\n")
        token = _bearer(request.headers.get("Authorization"))
        if not token:
            values = parse_qs(path.query).get("token") or []
            token = values[0].strip() if values else ""
        if not token or not hmac.compare_digest(token, self._token):
            return connection.respond(HTTPStatus.UNAUTHORIZED, "unauthorized\n")
        return None

    async def _on_connection(self, websocket) -> None:
        client = _Client(websocket)
        self._clients.add(client)
        logger.info("[android] client connected from %s", _safe(getattr(websocket, "remote_address", None)))
        try:
            async for raw in websocket:
                if isinstance(raw, (bytes, bytearray)):
                    continue
                try:
                    frame = json.loads(raw)
                except (TypeError, ValueError):
                    await client.send({"type": "error", "error": "invalid json"})
                    continue
                if not isinstance(frame, dict):
                    await client.send({"type": "error", "error": "a json object is required"})
                    continue
                try:
                    await self._on_frame(client, frame)
                except Exception:
                    logger.exception("[android] frame handling failed")
                    await client.send({"type": "error", "error": "frame failed"})
        except Exception:
            logger.debug("[android] connection ended", exc_info=True)
        finally:
            self._clients.discard(client)
            logger.info("[android] client disconnected")

    async def _on_frame(self, client: _Client, frame: dict) -> None:
        kind = str(frame.get("type") or "").strip()
        if kind == "ping":
            await client.send({"type": "pong", "time": time.time()})
        elif kind == "hello":
            await self._hello(client, frame)
        elif kind == "message":
            await self._inbound(client, frame)
        elif kind == "clarify":
            await self._answer_clarify(client, frame)
        elif kind == "approval":
            await self._answer_approval(client, frame)
        elif kind == "list":
            await client.send({"type": "chats", "chats": self._store.chats()})
        else:
            await client.send({"type": "error", "error": f"unknown frame type: {kind[:32]}"})

    async def _hello(self, client: _Client, frame: dict) -> None:
        chats = [item for item in (frame.get("chats") or []) if isinstance(item, str) and item.strip()]
        after = frame.get("after_seq")
        after = after if isinstance(after, int) and after >= 0 else 0
        client.chats.update(chats)
        await client.send(
            {
                "type": "hello",
                "platform": str(self.platform.value),
                "path": CHANNEL_PATH,
                "chats": [{"chat_id": chat, "name": self._store.name(chat), "last_seq": self._store.last_seq(chat)} for chat in chats],
            }
        )
        for chat in chats:
            for payload in self._store.replay(chat, after):
                await client.send(payload)

    # ── inbound ──────────────────────────────────────────────────────────────

    async def _inbound(self, client: _Client, frame: dict) -> None:
        chat_id = str(frame.get("chat_id") or "").strip()
        text = frame.get("text")
        if not chat_id or not isinstance(text, str) or not text.strip():
            await client.send({"type": "error", "error": "a chat_id and text are required"})
            return
        name = str(frame.get("chat_name") or "").strip() or None
        message_id = str(frame.get("message_id") or uuid.uuid4().hex)
        client.chats.add(chat_id)
        self._store.touch_chat(chat_id, name)
        source = self.build_source(
            chat_id=chat_id,
            chat_name=name or chat_id,
            chat_type="dm",
            user_id=self._principal,
            user_name=self._principal,
            message_id=message_id,
        )
        event = MessageEvent(
            text=text,
            message_type=MessageType.TEXT,
            user_id=self._principal,
            user_name=self._principal,
            source=source,
            message_id=message_id,
            raw_message=frame,
        )
        await self.handle_message(event)
        await client.send({"type": "accepted", "chat_id": chat_id, "message_id": message_id})

    async def _answer_clarify(self, client: _Client, frame: dict) -> None:
        clarify_id = str(frame.get("clarify_id") or "").strip()
        choice = frame.get("choice")
        if not clarify_id or not isinstance(choice, str):
            await client.send({"type": "error", "error": "a clarify_id and choice are required"})
            return
        from tools.clarify_gateway import resolve_gateway_clarify

        resolved = resolve_gateway_clarify(clarify_id, choice)
        await client.send({"type": "clarify.ack", "clarify_id": clarify_id, "resolved": bool(resolved)})

    async def _answer_approval(self, client: _Client, frame: dict) -> None:
        request_id = str(frame.get("request_id") or "").strip()
        choice = str(frame.get("choice") or "").strip()
        session_key = self._approvals.get(request_id)
        if choice not in APPROVAL_CHOICES or not session_key:
            await client.send({"type": "error", "error": "this approval card is not pending on this server"})
            return
        from tools.approval import resolve_gateway_approval_card

        count = resolve_gateway_approval_card(session_key, choice, request_id or None)
        if count:
            self._approvals.pop(request_id, None)
        await client.send({"type": "approval.ack", "request_id": request_id, "resolved": bool(count)})

    # ── outbound ─────────────────────────────────────────────────────────────

    async def _push(self, chat_id: str, payload: dict) -> bool:
        delivered = False
        for client in list(self._clients):
            if chat_id in client.chats:
                delivered = await client.send(payload) or delivered
        return delivered

    async def send(self, chat_id: str, content: str, reply_to: Optional[str] = None,
                   metadata: Optional[dict[str, Any]] = None) -> SendResult:
        chat_id = str(chat_id)
        payload: dict[str, Any] = {"type": "message", "chat_id": chat_id, "text": content}
        if reply_to:
            payload["reply_to"] = str(reply_to)
        seq = self._store.append(chat_id, payload)
        payload["seq"] = seq
        delivered = await self._push(chat_id, payload)
        return SendResult(success=True, message_id=str(seq),
                          raw_response={"delivered": delivered, "queued": not delivered, "seq": seq})

    def supports_draft_streaming(self, chat_type: Optional[str] = None,
                                 metadata: Optional[dict[str, Any]] = None,
                                 chat_id: Optional[str] = None) -> bool:
        return True

    async def send_draft(self, chat_id: str, draft_id: int, content: str,
                         metadata: Optional[dict[str, Any]] = None) -> SendResult:
        delivered = await self._push(
            str(chat_id),
            {"type": "draft", "chat_id": str(chat_id), "draft_id": int(draft_id), "text": content},
        )
        return SendResult(success=True, raw_response={"delivered": delivered})

    async def send_typing(self, chat_id: str, metadata=None) -> None:
        await self._push(str(chat_id), {"type": "typing", "chat_id": str(chat_id), "state": "working"})

    async def stop_typing(self, chat_id: str) -> None:
        await self._push(str(chat_id), {"type": "typing", "chat_id": str(chat_id), "state": "idle"})

    async def send_clarify(self, chat_id: str, question: str, choices: Optional[list],
                           clarify_id: str, session_key: str,
                           metadata: Optional[dict[str, Any]] = None) -> SendResult:
        if not choices:
            return await super().send_clarify(chat_id, question, choices, clarify_id, session_key, metadata=metadata)
        try:
            from tools.clarify_gateway import get_pending

            multi = bool(getattr(get_pending(clarify_id), "multi_select", False))
        except Exception:
            multi = False
        payload = {
            "type": "clarify",
            "chat_id": str(chat_id),
            "clarify_id": clarify_id,
            "question": question,
            "choices": [str(choice) for choice in choices],
            "multi": multi,
        }
        seq = self._store.append(str(chat_id), payload)
        payload["seq"] = seq
        delivered = await self._push(str(chat_id), payload)
        return SendResult(success=True, message_id=str(seq), raw_response={"delivered": delivered, "seq": seq})

    async def _send_exec_approval_prompt(self, prompt) -> SendResult:
        request_id = str(prompt.request_id or "")
        if len(self._approvals) >= 128:  # a card nobody answers must not grow the map forever
            self._approvals.pop(next(iter(self._approvals)), None)
        self._approvals[request_id] = prompt.session_key
        payload = {
            "type": "approval",
            "chat_id": str(prompt.chat_id),
            "request_id": request_id or None,
            "text": prompt.text,
            "actions": [list(action) for action in prompt.actions],
        }
        seq = self._store.append(str(prompt.chat_id), payload)
        payload["seq"] = seq
        delivered = await self._push(str(prompt.chat_id), payload)
        return SendResult(success=True, message_id=str(seq), raw_response={"delivered": delivered, "seq": seq})

    async def get_chat_info(self, chat_id: str) -> dict:
        chat_id = str(chat_id)
        return {"name": self._store.name(chat_id) or chat_id, "type": "dm", "chat_id": chat_id}


def _safe(value) -> str:
    if isinstance(value, (tuple, list)) and value:
        return str(value[0])
    return str(value or "unknown")
