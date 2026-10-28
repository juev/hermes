"""hermes-push: a Web Push to the subscribed phones after every finished turn.

The hook runs in the process that ran the turn (the gateway for Telegram, Matrix and cron, the
dashboard for its own chats); the send happens on a thread, so the turn does not wait for it.
"""

from __future__ import annotations

import logging
import threading

from . import store, webpush

logger = logging.getLogger(__name__)

SUBJECT = "mailto:hermes-push@localhost"
PREVIEW_CHARS = 600


def payload(session_id: str, platform: str, response: str, job: str | None) -> dict:
    """What the phone gets, encrypted: enough to show a notification without asking the server."""
    text = " ".join((response or "").split())
    return {
        "type": "turn",
        "session_id": session_id,
        "platform": platform or "",
        "job": job,
        "preview": text[:PREVIEW_CHARS],
    }


def deliver(message: dict, root=None) -> int:
    """Sends [message] to every subscription; dead endpoints are dropped. Returns how many got it."""
    key = store.vapid(root)
    if key is None:
        return 0
    sent = 0
    for sub in store.subscriptions(root):
        try:
            webpush.send(sub["endpoint"], sub["p256dh"], sub["auth"], message, key, SUBJECT)
            sent += 1
        except webpush.PushGone:
            store.unsubscribe(sub["endpoint"], root)
        except Exception:  # A push service that is down must not break anything else.
            logger.warning("hermes-push: send to %s failed", sub.get("name") or "a phone", exc_info=True)
    return sent


def register(ctx) -> None:
    def on_turn(session_id=None, assistant_response=None, platform=None, **_kwargs):
        if not session_id or not store.subscriptions():
            return
        execution = ctx.current_cron_execution()
        job = (getattr(execution, "job_name", None) or getattr(execution, "job_id", None)) if execution else None
        message = payload(session_id, platform or "", assistant_response or "", job)
        threading.Thread(target=deliver, args=(message,), name="hermes-push", daemon=True).start()

    ctx.register_hook("post_llm_call", on_turn)
