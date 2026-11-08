"""Local extracted commands with a default-success gating model, not Actions execution."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

class CiFlowControls(unittest.TestCase):
    def test_paired_discovery_failure_and_success_gate_probe(self):
        source = (Path(__file__).resolve().parents[3] / ".github/workflows/talkback-runtime.yml").read_text()
        job = source.split("  kvm-readiness:\n", 1)[1]
        self.assertNotIn("continue-on-error:", job)
        steps = re.split(r"(?m)^      - ", job.split("    steps:\n", 1)[1])[1:]
        units = [(i,s) for i,s in enumerate(steps) if "python3 -B -m unittest discover -s scripts/talkback-ci/tests -v" in s]
        self.assertEqual(len(units), 1)
        ui, unit = units[0]
        pi, probe = next((i,s) for i,s in enumerate(steps) if s.startswith("name: Probe KVM\n"))
        self.assertLess(ui, pi)
        def runblock(step):
            self.assertIn("        shell: bash\n", step)
            self.assertNotIn("if:", step)
            self.assertNotIn("continue-on-error:", step)
            return "\n".join(line[10:] for line in step.split("        run: |\n",1)[1].splitlines()) + "\n"
        unitrun, proberun = runblock(unit), runblock(probe)
        for passing in [False, True]:
            with self.subTest(passing=passing), tempfile.TemporaryDirectory(prefix="ci-flow-") as directory:
                root = Path(directory)
                tests = root / "scripts/talkback-ci/tests"
                tests.mkdir(parents=True)
                (tests / "test_tiny.py").write_text("import unittest\nclass Tiny(unittest.TestCase):\n def test_value(self): self.assertTrue(" + repr(passing) + ")\n")
                marker = root / "probe-marker"
                (root / "scripts/talkback-ci/probe.py").write_text("import os\nfrom pathlib import Path\nPath(os.environ['MARKER']).write_text('probe executed')\n")
                env = {"PATH":os.environ["PATH"], "HARNESS_ROOT":str(root), "KVM_DEVICE":"synthetic-unused", "MARKER":str(marker), "TMPDIR":str(root), "PYTHONDONTWRITEBYTECODE":"1"}
                shell = ["/bin/bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c"]
                result = subprocess.run(shell + [unitrun], cwd=root, env=env, capture_output=True, text=True, timeout=10)
                probe_result = None
                # Model only the reviewed normal-success ordering, using actual commands.
                if result.returncode == 0:
                    probe_result = subprocess.run(shell + [proberun], cwd=root, env=env, capture_output=True, text=True, timeout=10)
                print(json.dumps({"fixture_passing":passing,"unit_command":unitrun,"probe_command":proberun,"unit_exit":result.returncode,"unit_stdout":result.stdout,"unit_stderr":result.stderr,"probe_exit":None if probe_result is None else probe_result.returncode,"marker":marker.exists()}), flush=True)
                self.assertEqual(result.returncode == 0, passing)
                self.assertEqual(marker.exists(), passing)
                if passing:
                    self.assertEqual(probe_result.returncode, 0)
