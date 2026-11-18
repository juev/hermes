"""hermes-push: a Web Push to the subscribed phones after every finished turn.

The hook runs in the process that ran the turn (the gateway for Telegram, Matrix and cron, the
dashboard for its own chats); the send happens on a thread, so the turn does not wait for it.
"""

from __future__ import annotations

import logging
import sqlite3
import threading
from contextlib import closing

from . import store, webpush

logger = logging.getLogger(__name__)

SUBJECT = "mailto:hermes-push@localhost"
PREVIEW_CHARS = 600
# delegate_task runs each subagent as an agent of this platform; the turn of its parent chat notifies.
SUBAGENT = "subagent"


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


def _is_one_shot(session_id: str, platform: str | None) -> bool:
    if platform == "oneshot":
        return True

    # The hook reports the turn's transport (e.g. local or cli), not the session's
    # creator. Read only provenance from the active profile; do not open a store
    # that can initialize or migrate the database during a notification callback.
    from hermes_constants import get_hermes_home

    uri = (get_hermes_home() / "state.db").absolute().as_uri() + "?mode=ro"
    try:
        with closing(sqlite3.connect(uri, uri=True, timeout=0.05)) as db:
            try:
                row = db.execute("SELECT source, created_source FROM sessions WHERE id = ?", (session_id,)).fetchone()
            except sqlite3.OperationalError as exc:
                if str(exc) != "no such column: created_source":
                    raise
                # Older Hermes releases only record the session source.
                row = db.execute("SELECT source FROM sessions WHERE id = ?", (session_id,)).fetchone()
    except sqlite3.Error:
        # Missing or unavailable provenance is not evidence that a chat is a smoke run.
        logger.debug("hermes-push: session provenance unavailable", exc_info=True)
        return False
    return row is not None and "oneshot" in row


def register(ctx) -> None:
    def on_turn(session_id=None, assistant_response=None, platform=None, **_kwargs):
        if not session_id or platform == SUBAGENT or not store.subscriptions():
            return
        if _is_one_shot(session_id, platform):
            return
        execution = ctx.current_cron_execution()
        job = (getattr(execution, "job_name", None) or getattr(execution, "job_id", None)) if execution else None
        message = payload(session_id, platform or "", assistant_response or "", job)
        threading.Thread(target=deliver, args=(message,), name="hermes-push", daemon=True).start()

    ctx.register_hook("post_llm_call", on_turn)
