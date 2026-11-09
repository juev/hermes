"""Bounded, connection-local relay; no global subscriptions or diagnostic dumps."""
import asyncio
from contextlib import suppress

from starlette.websockets import WebSocketDisconnect

from .bridge import Bridge


async def relay(ws, native, store, owner):
    bridge = Bridge(native, store, owner)
    sending = asyncio.Lock()
    joining = 0
    deferred = []
    handlers = set()

    async def send(frame):
        async with sending:
            await ws.send_json(frame)

    async def request(frame):
        nonlocal joining
        method = frame.get("method")
        is_join = method in ("session.create", "session.resume")
        if is_join:
            joining += 1
        try:
            result = await bridge.request(method, frame.get("params") or {})
            await send({"jsonrpc": "2.0", "id": frame["id"], "result": result})
        except (PermissionError, ValueError, ConnectionError, RuntimeError) as exc:
            await send({"jsonrpc": "2.0", "id": frame["id"], "error": {"code": -32000, "message": str(exc)[:200]}})
        except Exception:
            await send({"jsonrpc": "2.0", "id": frame["id"], "error": {"code": -32000, "message": "Native operation failed"}})
        finally:
            if is_join:
                joining -= 1
                if joining == 0:
                    frames, deferred[:] = list(deferred), []
                    for pending in frames:
                        projected = bridge.forward(pending)
                        if projected is not None:
                            await send(projected)

    async def incoming():
        while True:
            frame = await ws.receive_json()
            if not isinstance(frame, dict) or not isinstance(frame.get("id"), (str, int)):
                raise ValueError("Expected an identified JSON-RPC frame")
            if "method" not in frame:
                await bridge.respond(frame)
                continue
            if not isinstance(frame["method"], str) or not isinstance(frame.get("params", {}), dict):
                raise ValueError("Invalid JSON-RPC method or params")
            if len(handlers) >= 32:
                raise ConnectionError("Too many outstanding requests")
            task = asyncio.create_task(request(frame))
            handlers.add(task)
            task.add_done_callback(handlers.discard)

    async def outgoing():
        while True:
            frame = await native.events.get()
            if isinstance(frame, Exception):
                raise frame
            if joining:
                if len(deferred) >= 256:
                    raise ConnectionError("Join replay overflow; reconnect to recover")
                deferred.append(frame)
                continue
            projected = bridge.forward(frame)
            if projected is not None:
                await send(projected)

    tasks = {asyncio.create_task(incoming()), asyncio.create_task(outgoing())}
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
    except WebSocketDisconnect:
        pass
    finally:
        tasks.update(handlers)
        for task in tasks:
            task.cancel()
        for task in tasks:
            with suppress(asyncio.CancelledError, WebSocketDisconnect, ConnectionError):
                await task
