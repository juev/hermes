"""Channel registry tests on a real private SQLite file."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

from unittest.mock import patch
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_registry", ROOT / "registry.py")
assert spec is not None and spec.loader is not None
registry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(registry)


class RegistryTest(unittest.TestCase):
    def test_trusted_compaction_alias_preserves_one_logical_channel_and_cannot_steal_a_session(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            root = Path(directory)
            store = registry.Registry(root)
            store.claim("alice", "logical")
            store.claim("bob", "foreign")
            store.alias("logical", "physical-1")
            self.assertEqual("alice", store.owner("physical-1"), "A trusted post_compaction event must carry channel ownership to the new transcript")
            store.alias("physical-1", "physical-2")
            self.assertEqual("logical", store.logical("physical-2"))
            self.assertTrue(store.owns("alice", "physical-2"))
            self.assertEqual(["logical"], store.sessions("alice"))
            with self.assertRaises(PermissionError):
                store.alias("logical", "foreign")
            with self.assertRaises(PermissionError):
                store.claim("bob", "physical-1")
            store.alias("unrelated", "unclaimed")
            self.assertIsNone(store.owner("unclaimed"))
            self.assertEqual("logical", registry.Registry(root).logical("physical-2"))

    def test_each_operation_closes_its_actual_database_connection(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            opened = []
            real_connect = sqlite3.connect

            def connect(*args, **kwargs):
                connection = real_connect(*args, **kwargs)
                opened.append(connection)
                return connection

            try:
                with patch.object(registry.sqlite3, "connect", connect):
                    store = registry.Registry(Path(directory))
                    store.claim("alice", "owned")
                    self.assertTrue(store.owns("alice", "owned"))
                    self.assertEqual(["owned"], store.sessions("alice"))
                self.assertEqual(4, len(opened))
                for connection in opened:
                    with self.assertRaises(sqlite3.ProgrammingError, msg="Database connection leaked after operation"):
                        connection.execute("SELECT 1")
            finally:
                for connection in opened:
                    connection.close()

    def test_owned_channel_persists_without_admitting_or_stealing_foreign_sessions(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            root = Path(directory)
            first = registry.Registry(root)
            first.claim("alice", "session-a")
            self.assertTrue(first.owns("alice", "session-a"))
            self.assertFalse(first.owns("alice", "telegram-session"), "A guessed unrelated id must not be admitted")
            second = registry.Registry(root)
            self.assertTrue(second.owns("alice", "session-a"))
            self.assertFalse(second.owns("bob", "session-a"))
            with self.assertRaises(PermissionError):
                second.claim("bob", "session-a")
            self.assertTrue(first.owns("alice", "session-a"))
            self.assertEqual(["session-a"], second.sessions("alice"))
            self.assertEqual([], second.sessions("bob"))
            self.assertEqual(0o600, (root / "channels.sqlite3").stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
