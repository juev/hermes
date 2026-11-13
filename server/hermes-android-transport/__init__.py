"""Android channel as a gateway platform: ``ctx.register_platform("android", ...)``.

The phone is a chat client of the gateway, exactly like a Telegram chat: one
gateway session per conversation, one readable reply stream, native clarify and
approval answers. No dashboard router, no session registry, no projection and no
JSON-RPC bridge are involved — the platform layer already delivers only what a
platform may see.
"""

import logging

logger = logging.getLogger(__name__)

PLATFORM_HINT = (
    "You are on the Hermes Android app channel. Replies are delivered as chat "
    "messages: Markdown renders (bold, italics, inline code, fenced code blocks, "
    "lists, links). There is one conversation per chat id; the user cannot see "
    "model reasoning or tool payloads, so state anything they need in the reply "
    "itself instead of relying on transcript detail. Long work is shown as a "
    "single working indicator, so keep interim commentary meaningful rather than "
    "per-token."
)


def check_requirements() -> bool:
    """Passive probe: the transport needs only ``websockets``, which ships with core."""
    try:
        import websockets  # noqa: F401
    except ImportError:
        return False
    return True


def validate_config(config) -> bool:
    """A channel without a bearer token is refused instead of silently unauthenticated."""
    return bool(_token(config))


def is_connected(config) -> bool:
    return bool(_token(config))


def _token(config):
    extra = getattr(config, "extra", None) or {}
    value = extra.get("token")
    if isinstance(value, str) and value.strip():
        return value.strip()
    try:
        from gateway.platforms._shared import get_scoped_secret
        value = get_scoped_secret("ANDROID_CHANNEL_TOKEN")
    except Exception:  # pragma: no cover - multiplex scope unavailable outside a gateway
        import os
        value = os.environ.get("ANDROID_CHANNEL_TOKEN")
    return (value or "").strip()


def register(ctx) -> None:
    from .adapter import AndroidChannelAdapter

    ctx.register_platform(
        name="android",
        label="Android",
        adapter_factory=lambda cfg: AndroidChannelAdapter(cfg),
        check_fn=check_requirements,
        validate_config=validate_config,
        is_connected=is_connected,
        required_env=["ANDROID_CHANNEL_TOKEN"],
        install_hint="No extra packages: the transport uses core's websockets dependency",
        emoji="\U0001f4f1",
        platform_hint=PLATFORM_HINT,
        cron_deliver_env_var="ANDROID_HOME_CHANNEL",
    )
