"""Hermes Android backend extension; intentionally adds no model-facing tools."""
import logging
import threading

log = logging.getLogger(__name__)


def services(root=None, sender=None):
    from .registry import Registry
    from .push import PushService
    if root is None:
        from plugins.plugin_storage import plugin_data_dir
        root = plugin_data_dir("hermes-android")
    registry = Registry(root)
    return registry, PushService(registry, sender=sender)


def register(ctx, *, root=None, sender=None):
    """Supported completion/compaction hooks; the dashboard loader mounts HTTP/WS."""
    registry, push = services(root, sender)
    capacity = threading.BoundedSemaphore(8)

    def complete(session_id=None, assistant_response=None, platform=None, **_):
        if not session_id or platform == "subagent" or registry.owner(session_id) is None:
            return
        if not capacity.acquire(blocking=False):
            log.warning("Android channel push capacity reached")
            return

        def deliver():
            try:
                push.deliver(session_id, assistant_response)
            finally:
                capacity.release()

        threading.Thread(target=deliver, name="hermes-android-push", daemon=True).start()

    def compact(session_id=None, old_session_id=None, **_):
        registry.alias(old_session_id, session_id)

    ctx.register_hook("post_llm_call", complete)
    ctx.register_hook("post_compaction", compact)
