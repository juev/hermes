"""Short-lived, one-use upgrade credentials; never persisted or logged."""
from copy import deepcopy
import secrets
import threading
import time


class Tickets:
    def __init__(self, clock=time.monotonic, ttl=20, maximum=128):
        self.clock, self.ttl, self.maximum = clock, ttl, maximum
        self.entries = {}
        self.lock = threading.Lock()

    def issue(self, entry):
        with self.lock:
            now = self.clock()
            self.entries = {key: value for key, value in self.entries.items() if value[0] > now}
            if len(self.entries) >= self.maximum:
                raise RuntimeError("Too many pending connections")
            token = secrets.token_urlsafe(32)
            self.entries[token] = (now + self.ttl, deepcopy(entry))
            return token

    def take(self, token):
        with self.lock:
            entry = self.entries.pop(token, None)
            return entry[1] if entry is not None and entry[0] > self.clock() else None
