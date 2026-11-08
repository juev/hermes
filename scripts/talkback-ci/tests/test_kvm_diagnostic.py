"""Real harmless-device diagnostic contract through the declared workflow caller."""
import errno
import fcntl
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest

class KvmDiagnosticTest(unittest.TestCase):
    def test_actual_failure_caller_preserves_safe_diagnostic(self):
        fd = os.open("/dev/null", os.O_RDWR | os.O_CLOEXEC)
        try:
            self.assertTrue(stat.S_ISCHR(os.fstat(fd).st_mode))
            with self.assertRaises(OSError) as caught:
                fcntl.ioctl(fd, 0xAE00, 0)
            self.assertEqual(caught.exception.errno, errno.ENOTTY)
            self.assertEqual(errno.ENOTTY, 25)
        finally:
            os.close(fd)
        root = Path(__file__).resolve().parents[3]
        source = (root / ".github/workflows/talkback-runtime.yml").read_text()
        self.assertEqual(source.count("      - name: Probe KVM\n"), 1)
        step = source.split("      - name: Probe KVM\n", 1)[1]
        self.assertIn("        shell: bash\n", step)
        self.assertIn("          HARNESS_ROOT: ${{ github.workspace }}\n", step)
        self.assertIn("          KVM_DEVICE: /dev/kvm\n", step)
        lines = step.split("        run: |\n", 1)[1].splitlines()
        self.assertTrue(lines and all(s.startswith("          ") for s in lines))
        command = "\n".join(s[10:] for s in lines) + "\n"
        with tempfile.TemporaryDirectory(prefix="kvm-diagnostic-", dir=os.environ.get("TMPDIR") or os.environ.get("RUNNER_TEMP")) as directory:
            env = {"PATH":os.environ["PATH"], "HARNESS_ROOT":str(root), "KVM_DEVICE":"/dev/null", "TMPDIR":directory, "PYTHONDONTWRITEBYTECODE":"1"}
            result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command], cwd=directory, env=env, capture_output=True, text=True, timeout=10)
        print(json.dumps({"calibration_errno":25,"calibration_fd_closed_before_caller":True,"caller_exit":result.returncode,"stdout":result.stdout,"stderr":result.stderr}), flush=True)
        self.assertEqual(result.returncode, 2)
        payload = json.loads(result.stdout)
        self.assertEqual(payload.get("status"), "KVM_UNAVAILABLE")
        self.assertEqual(payload.get("reason"), "kvm_unavailable")
        self.assertNotIn("Traceback", result.stderr)
        self.assertIsInstance(payload.get("diagnostic"), dict, "KVM_REFUSAL_DIAGNOSTIC_MISSING")
        self.assertEqual(payload, {"status":"KVM_UNAVAILABLE", "reason":"kvm_unavailable", "diagnostic":{"primary":{"stage":"api_ioctl","errno":25},"api_version":None,"vm_creation_observed":False,"cleanup":{"vm":{"status":"not_acquired","errno":None},"device":{"status":"closed","errno":None}}}})
