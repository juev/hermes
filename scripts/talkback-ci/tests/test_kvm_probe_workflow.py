import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/talkback-ci"))
from kvm_gate import KvmUnavailable, require_kvm


class ProbeWorkflowTest(unittest.TestCase):
    def test_actual_workflow_propagates_non_kvm_refusal(self):
        self.assertTrue(stat.S_ISCHR(os.stat("/dev/null").st_mode))
        with self.assertRaises(KvmUnavailable):
            require_kvm("/dev/null")
        source = (ROOT / ".github/workflows/talkback-runtime.yml").read_text()
        self.assertEqual(source.count("      - name: Probe KVM\n"), 1)
        step = source.split("      - name: Probe KVM\n", 1)[1]
        self.assertIn("        shell: bash\n", step)
        self.assertIn("          HARNESS_ROOT: ${{ github.workspace }}\n", step)
        self.assertIn("          KVM_DEVICE: /dev/kvm\n", step)
        lines = step.split("        run: |\n", 1)[1].splitlines()
        self.assertTrue(lines and all(line.startswith("          ") for line in lines))
        run = "\n".join(line[10:] for line in lines) + "\n"
        with tempfile.TemporaryDirectory(prefix="kvm-workflow-") as directory:
            environment = {"PATH": os.environ["PATH"], "HARNESS_ROOT": str(ROOT),
                           "KVM_DEVICE": "/dev/null", "PYTHONDONTWRITEBYTECODE": "1"}
            # Actions explicit shell:bash semantics: bash --noprofile --norc -e -o pipefail.
            result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", run],
                                    cwd=directory, env=environment, capture_output=True, text=True, timeout=10)
        print(json.dumps({"run_sha256": hashlib.sha256(run.encode()).hexdigest(),
                          "fixture": "non-KVM character device /dev/null", "exit": result.returncode,
                          "stdout": result.stdout, "stderr": result.stderr}), flush=True)
        self.assertNotEqual(result.returncode, 0, "CLI_ACCEPTED_NON_KVM_DEVICE")
        proof = json.loads(result.stdout)
        self.assertEqual(proof["status"], "KVM_UNAVAILABLE")
        self.assertEqual(proof["reason"], "kvm_unavailable")
        self.assertNotIn("Traceback", result.stderr)
