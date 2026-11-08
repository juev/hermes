"""Narrow contract for the actual hardware readiness workflow configuration."""
from pathlib import Path
import re
import shlex
import unittest

class KvmCiGateTest(unittest.TestCase):
    def test_unit_suite_is_enforced_before_hardware_probe(self):
        source = (Path(__file__).resolve().parents[3] / ".github/workflows/talkback-runtime.yml").read_text()
        self.assertIn("  kvm-readiness:\n", source)
        job = source.split("  kvm-readiness:\n", 1)[1]
        self.assertIn("    runs-on: ubuntu-24.04\n", job)
        self.assertEqual(job.count("      - name: Probe KVM\n"), 1)
        steps = re.split(r"(?m)^      - ", job.split("    steps:\n", 1)[1])[1:]
        probe = next(i for i, step in enumerate(steps) if step.startswith("name: Probe KVM\n"))
        self.assertIn('probe.py" --device "$KVM_DEVICE"', steps[probe])
        candidates = []
        for i, step in enumerate(steps):
            if "        run: |\n" not in step:
                continue
            run = step.split("        run: |\n", 1)[1]
            for line in run.splitlines():
                tokens = shlex.split(line.strip())
                if tokens[:5] == ["python3", "-B", "-m", "unittest", "discover"] and "-s" in tokens:
                    start = tokens.index("-s")
                    if start + 1 < len(tokens) and tokens[start + 1] == "scripts/talkback-ci/tests":
                        candidates.append((i, step, tokens))
        self.assertTrue(candidates, "KVM_UNIT_SUITE_NOT_ENFORCED")
        self.assertEqual(len(candidates), 1)
        index, step, tokens = candidates[0]
        self.assertLess(index, probe)
        self.assertIn("        shell: bash\n", step)
        self.assertNotIn("continue-on-error:", job)
        self.assertNotIn("if:", step)
        self.assertNotIn("if:", steps[probe])
        self.assertFalse(any(t in tokens for t in ["||", "&&", ";", "|", "true", "-k", "-p"]))
        self.assertNotIn("||", step)
        self.assertNotIn("continue-on-error:", step)
