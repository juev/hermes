"""Synthetic command replay only; never executes real device or sudo operations."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

class DeviceAccessControls(unittest.TestCase):
    def test_six_synthetic_access_paths(self):
        source = (Path(__file__).resolve().parents[3] / ".github/workflows/talkback-runtime.yml").read_text()
        step = source.split("      - name: Prepare disposable runner KVM access\n",1)[1].split("      - name: Probe KVM\n",1)[0]
        self.assertIn("        shell: bash\n",step)
        self.assertNotIn("if:",step)
        self.assertNotIn("continue-on-error:",source)
        command = "\n".join(s[10:] for s in step.split("        run: |\n",1)[1].splitlines()) + "\n"
        probe = source.split("      - name: Probe KVM\n",1)[1]
        probe_command = "\n".join(s[10:] for s in probe.split("        run: |\n",1)[1].splitlines()) + "\n"
        for scenario in ("nonchar","already","grant","chownfail","chmodfail","unresolved"):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR") or os.environ.get("RUNNER_TEMP")) as directory:
                root=Path(directory); tools=root/"bin";tools.mkdir()
                stub = """#!/bin/bash
printf '%s' "${0##*/}" >> "$CALLS"
for arg in "$@"; do printf ' <%s>' "$arg" >> "$CALLS"; done
printf '\n' >> "$CALLS"
case "${0##*/}" in
 id) case "$*" in '-u') echo 1001;; '-g') echo 1002;; *) exit 90;; esac;;
 stat) [[ "$#" = 5 && "$1" = -L && "$2" = -c && "$3" = '%u %g %a' && "$4" = /dev/kvm ]] && exit 91
       [[ "$#" = 4 && "$1" = -L && "$2" = -c && "$3" = '%u %g %a' && "$4" = /dev/kvm ]] || exit 92
       echo '0 0 600';;
 sudo) [[ "$1" = -n && "$3" = -- && "$5" = /dev/kvm && "$#" = 5 ]] || exit 93
       case "$2:$4" in
        chown:1001:1002) [[ "$SCENARIO" != chownfail ]] || exit 41;;
        chmod:u+rw) [[ "$SCENARIO" != chmodfail ]] || exit 42; : > "$GRANTED";;
        *) exit 94;; esac;;
 *) exit 95;; esac
"""
                for name in ('id','stat','sudo'):
                    path=tools/name;path.write_text(stub);path.chmod(0o700)
                bashenv=root/'bashenv';bashenv.write_text("""test() {
 printf 'test <%s> <%s>\n' "$1" "$2" >> "$CALLS"
 [[ "$#" = 2 && "$2" = /dev/kvm ]] || return 96
 case "$1" in
 -c) [[ "$SCENARIO" != nonchar ]];;
 -r|-w) [[ "$SCENARIO" = already || ( -f "$GRANTED" && "$SCENARIO" != unresolved ) ]];;
 *) return 97;; esac
}
""")
                calls=root/'calls';granted=root/'granted';marker=root/'marker'
                scripts=root/'scripts/talkback-ci';scripts.mkdir(parents=True)
                (scripts/'probe.py').write_text("import os\nfrom pathlib import Path\nPath(os.environ['MARKER']).write_text('executed')\n")
                env={'PATH':str(tools)+':'+os.environ['PATH'],'BASH_ENV':str(bashenv),'CALLS':str(calls),'GRANTED':str(granted),'SCENARIO':scenario,'HARNESS_ROOT':str(root),'KVM_DEVICE':'synthetic-unused','MARKER':str(marker),'TMPDIR':str(root),'PYTHONDONTWRITEBYTECODE':'1'}
                shell=['/bin/bash','--noprofile','--norc','-e','-o','pipefail','-c']
                result=subprocess.run(shell+[command],cwd=root,env=env,capture_output=True,text=True,timeout=10)
                probe_result=None
                if result.returncode==0: probe_result=subprocess.run(shell+[probe_command],cwd=root,env=env,capture_output=True,text=True,timeout=10)
                log=calls.read_text();sudo=[s for s in log.splitlines() if s.startswith('sudo ')]
                expected=0 if scenario in ('nonchar','already') else 1 if scenario=='chownfail' else 2
                print(json.dumps({'scenario':scenario,'exit':result.returncode,'calls':log,'stdout':result.stdout,'stderr':result.stderr,'probe_marker':marker.exists()}),flush=True)
                self.assertEqual(len(sudo),expected)
                self.assertEqual(result.returncode==0,scenario in ('already','grant'))
                self.assertEqual(marker.exists(),scenario in ('already','grant'))
                if sudo:self.assertEqual(sudo[0],'sudo <-n> <chown> <--> <1001:1002> </dev/kvm>')
                if len(sudo)==2:self.assertEqual(sudo[1],'sudo <-n> <chmod> <--> <u+rw> </dev/kvm>')
                if probe_result is not None:self.assertEqual(probe_result.returncode,0)
