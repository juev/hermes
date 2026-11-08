import contextlib
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import probe

class ProbeControls(unittest.TestCase):
    def test_synthetic_success_forwards_device(self):
        out = io.StringIO()
        with patch.object(probe, "require_kvm", return_value={"api_version": 12, "vm_creation_observed": True}) as gate, contextlib.redirect_stdout(out):
            code = probe.main(["--device", "synthetic-device"])
        gate.assert_called_once_with("synthetic-device")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out.getvalue()), {"status": "KVM_READY", "api_version": 12, "vm_creation_observed": True})

    def test_typed_refusal_default_device_safe_json(self):
        out, err = io.StringIO(), io.StringIO()
        with patch.object(probe, "require_kvm", side_effect=probe.KvmUnavailable("private diagnostic")) as gate, contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = probe.main([])
        gate.assert_called_once_with("/dev/kvm")
        self.assertNotEqual(code, 0)
        self.assertEqual(json.loads(out.getvalue()), {"status": "KVM_UNAVAILABLE", "reason": "kvm_unavailable"})
        self.assertEqual(err.getvalue(), "")
        self.assertNotIn("private diagnostic", out.getvalue())
