"""Public JSON-RPC over WebSocket; private work is filtered before buffering."""
import asyncio
from contextlib import suppress
import json

from . import projection


class NativeError(Exception):
    def __init__(self, error):
        self.code = error.get("code", -32000) if isinstance(error, dict) else -32000
        super().__init__("Native request refused")


class NativeClient:
    def __init__(self, websocket):
        self.websocket = websocket
        self.events = asyncio.Queue(maxsize=256)
        self.pending = {}
        self.sequence = 0
        self.reader = None
        self.native_bytes = 0
        self.projected_bytes = 0
        self.canonical = False
        self.ready = asyncio.get_running_loop().create_future()

    async def start(self):
        self.reader = asyncio.create_task(self._read())
        await asyncio.wait_for(asyncio.shield(self.ready), timeout=15)

    async def _read(self):
        failure = ConnectionError("Native gateway disconnected; rejoin to recover")
        try:
            async for raw in self.websocket:
                self.native_bytes += len(raw.encode("utf-8") if isinstance(raw, str) else raw)
                frame = json.loads(raw)
                if not isinstance(frame, dict):
                    raise ValueError("Expected one native JSON-RPC object")
                future = self.pending.get(frame.get("id"))
                if future is not None:
                    if not future.done():
                        if "error" in frame:
                            future.set_exception(NativeError(frame["error"]))
                        else:
                            future.set_result(frame.get("result", {}))
                    continue
                params = frame.get("params") or {}
                if params.get("type") == "gateway.ready":
                    self.canonical = (params.get("payload") or {}).get("session_authority") is True
                    if not self.ready.done():
                        self.ready.set_result(None)
                projected = projection.event(frame)
                if projected is not None:
                    self.projected_bytes += len(json.dumps(projected, ensure_ascii=False).encode("utf-8"))
                    await asyncio.wait_for(self.events.put(projected), timeout=30)
        except asyncio.CancelledError:
            raise
        except Exception:
            # Never log payloads, bearer/ticket URLs or private native diagnostics.
            failure = ConnectionError("Native stream failed; rejoin to recover")
        finally:
            if not self.ready.done():
                self.ready.set_exception(failure)
                self.ready.exception()
            for future in tuple(self.pending.values()):
                if not future.done():
                    future.set_exception(failure)
            # On overflow explicitly end the transport, forcing snapshot recovery;
            # do not silently continue after discarding a visible event.
            if self.events.full():
                self.events.get_nowait()
            self.events.put_nowait(failure)

    async def rpc(self, method, **params):
        if self.reader is None or self.reader.done():
            raise ConnectionError("Native connection is not ready")
        self.sequence += 1
        request_id = "android-proxy-" + str(self.sequence)
        future = asyncio.get_running_loop().create_future()
        self.pending[request_id] = future
        try:
            await self.websocket.send(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}))
            return await asyncio.wait_for(future, timeout=120)
        finally:
            self.pending.pop(request_id, None)
            if future.done() and not future.cancelled():
                future.exception()

    async def respond(self, frame):
        await self.websocket.send(json.dumps(frame))

    async def stop(self):
        if self.reader is not None:
            self.reader.cancel()
            with suppress(asyncio.CancelledError):
                await self.reader
