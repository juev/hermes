import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("android_tickets", Path(__file__).resolve().parents[1] / "tickets.py")
assert spec is not None and spec.loader is not None
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class TicketTest(unittest.TestCase):
    def test_tickets_are_single_use_expiring_bounded_and_not_predictable(self):
        now = [10.0]
        tickets = module.Tickets(clock=lambda: now[0], ttl=20, maximum=1)
        entry = {"owner": "alice", "native_ticket": "synthetic-native-ticket"}
        token = tickets.issue(entry)
        self.assertEqual(entry, tickets.take(token))
        self.assertIsNone(tickets.take(token), "A consumed upgrade ticket must never authenticate twice")
        next_token = tickets.issue(entry)
        self.assertNotEqual(token, next_token)
        self.assertGreaterEqual(len(next_token), 32)
        with self.assertRaises(RuntimeError):
            tickets.issue(entry)
        now[0] = 31.0
        self.assertIsNone(tickets.take(next_token))
        self.assertIsNone(tickets.take("guessed"))
        self.assertIsNotNone(tickets.issue(entry))


if __name__ == "__main__":
    unittest.main()
