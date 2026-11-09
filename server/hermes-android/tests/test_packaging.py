"""Actual installed Hermes dashboard loader, isolated storage/profile fixture."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from hermes_cli.web_server_dashboard import _dashboard_plugin_entry, _mount_one_plugin_api, _plugin_route_secret_scope

ROOT = Path(__file__).resolve().parents[1]


class PackagingTest(unittest.TestCase):
    def test_documented_enabled_user_backend_mounts_its_own_authenticated_routes(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            app = FastAPI()

            async def isolated_profile_scope():
                yield  # Test the loader, not the production profile manager/config.

            app.dependency_overrides[_plugin_route_secret_scope] = isolated_profile_scope
            manifest = json.loads((ROOT / "dashboard/manifest.json").read_text())
            plugin = _dashboard_plugin_entry(manifest, "hermes-android", ROOT / "dashboard", "user")
            with patch("hermes_cli.plugin_isolation.isolation_mode", return_value="in_process"), patch("plugins.plugin_storage.plugin_data_dir", return_value=Path(directory)):
                self.assertTrue(_mount_one_plugin_api(app, plugin, {"hermes-android"}, set()))
                with TestClient(app) as client:
                    response = client.get("/api/plugins/hermes-android/api/sessions")
                    self.assertEqual(401, response.status_code, "A discovered backend must mount the actual channel routes and fail closed without sign-in")
                    self.assertEqual(401, client.get("/api/plugins/hermes-android/push/key").status_code)
            self.assertTrue((Path(directory) / "channels.sqlite3").is_file())


if __name__ == "__main__":
    unittest.main()
