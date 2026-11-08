"""Configuration contract only: never executes permission commands or KVM."""
from pathlib import Path
import re
import unittest

class KvmDeviceAccessTest(unittest.TestCase):
    def test_disposable_runner_prepares_only_kvm_device_access(self):
        source = (Path(__file__).resolve().parents[3] / ".github/workflows/talkback-runtime.yml").read_text()
        self.assertIn("  kvm-readiness:\n", source)
        job = source.split("  kvm-readiness:\n", 1)[1]
        self.assertIn("    runs-on: ubuntu-24.04\n", job)
        steps = re.split(r"(?m)^      - ", job.split("    steps:\n", 1)[1])[1:]
        units = [i for i,s in enumerate(steps) if "python3 -B -m unittest discover -s scripts/talkback-ci/tests -v" in s]
        probes = [i for i,s in enumerate(steps) if s.startswith("name: Probe KVM\n")]
        self.assertEqual(len(units), 1)
        self.assertEqual(len(probes), 1)
        self.assertLess(units[0], probes[0])
        self.assertIn('probe.py" --device "$KVM_DEVICE"', steps[probes[0]])
        candidates = [(i,s) for i,s in enumerate(steps) if "sudo -n chown --" in s and "sudo -n chmod --" in s]
        self.assertTrue(candidates, "KVM_RUNNER_DEVICE_ACCESS_NOT_PREPARED")
        self.assertEqual(len(candidates), 1)
        index, step = candidates[0]
        self.assertLess(units[0], index)
        self.assertLess(index, probes[0])
        self.assertIn("        shell: bash\n", step)
        self.assertNotIn("continue-on-error:", job)
        for item in (step, steps[units[0]], steps[probes[0]]):
            self.assertNotIn("if:", item)
        run = step.split("        run: |\n", 1)[1]
        self.assertIn('sudo -n chown -- "$(id -u):$(id -g)" /dev/kvm', run)
        self.assertIn('sudo -n chmod -- u+rw /dev/kvm', run)
        self.assertRegex(run, r'(?s)(?:test|\[)[^\n]*-c /dev/kvm.*sudo -n chown')
        self.assertRegex(run, r'(?s)if[^\n]*-r /dev/kvm[^\n]*-w /dev/kvm.*(?:else|!|then)')
        self.assertGreaterEqual(run.count('-c /dev/kvm'), 2)
        self.assertGreaterEqual(run.count('-r /dev/kvm'), 2)
        self.assertGreaterEqual(run.count('-w /dev/kvm'), 2)
        self.assertGreaterEqual(run.count('stat '), 2)
        self.assertIn('%u', run)
        self.assertIn('%g', run)
        self.assertIn('%a', run)
        for forbidden in ('|| true', 'a+rw', '0666', '0777', 'sudo -E', 'chmod 666', '/dev/*'):
            self.assertNotIn(forbidden, run)
