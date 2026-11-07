"""Native caller contracts; parse this repository's indented step structure only.

This is deliberately not a general YAML parser. Run blocks are read by indentation,
so comments and similarly named plugin commands cannot satisfy a native step.
"""
from pathlib import Path
import re
import os
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
SUITE_COMMAND = 'python3 -B -m unittest discover -s scripts/tests -v'
ALIASES = ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'NDK_HOME', 'NDK_ROOT')


def workflow_steps(path, job):
    lines = path.read_text().splitlines()
    start = lines.index('  ' + job + ':') + 1
    end = next((i for i in range(start, len(lines))
                if re.match(r'^  [\w-]+:', lines[i])), len(lines))
    starts = [i for i in range(start, end) if lines[i].startswith('      - ')]
    steps = []
    for first, last in zip(starts, starts[1:] + [end]):
        step = {}
        i = first
        while i < last:
            line = lines[i]
            match = re.match(r'^(?:      - |        )([\w-]+):\s*(.*)$', line)
            if match:
                key, value = match.groups()
                if value == '|':
                    stop = i + 1
                    while stop < last and (not lines[stop].strip() or lines[stop].startswith('          ')):
                        stop += 1
                    value = textwrap.dedent('\n'.join(lines[i + 1:stop])).rstrip() + '\n'
                    i = stop - 1
                step[key] = value
            i += 1
        steps.append(step)
    return steps


class NativeWorkflowTests(unittest.TestCase):
    def assert_gate(self, filename, job):
        steps = workflow_steps(ROOT / '.github/workflows' / filename, job)
        gates = [i for i, step in enumerate(steps) if step.get('run', '').strip() == SUITE_COMMAND]
        self.assertEqual(len(gates), 1, f'{filename}: native suite must execute exactly once in {job}')
        gate = gates[0]
        self.assertNotIn('if', steps[gate], 'suite must run on every job trigger')
        self.assertNotIn('continue-on-error', steps[gate])
        self.assertTrue(any(s.get('uses', '').startswith('actions/setup-java@') for s in steps[:gate]))
        setup = next(i for i, s in enumerate(steps) if 'revision=$(cat scripts/android-ndk-version)' in s.get('run', ''))
        self.assertLess(setup, gate, 'suite must exercise the selected four-alias environment')
        for i, step in enumerate(steps):
            run = step.get('run', '')
            if './scripts/build-tailnet.sh' in run or './gradlew' in run or 'gh release create' in run:
                self.assertLess(gate, i, 'suite failure must block native/build/publication')
                self.assertNotIn('continue-on-error', step)
                condition = step.get('if', '')
                self.assertNotIn('always()', condition)
                self.assertNotIn('failure()', condition)
            if step.get('uses', '').startswith('actions/upload-artifact@'):
                self.assertLess(gate, i)
                if step.get('name') != 'Retain native evidence' and step.get('if') != 'failure()':
                    self.assertNotIn('if', step, 'product artifacts require default success')
        return steps

    def test_actual_ndk_install_admits_only_success(self):
        for filename, job in [('ci.yml', 'build'), ('release.yml', 'release'),
                              ('reconnect-stress.yml', 'stress')]:
            steps = workflow_steps(ROOT / '.github/workflows' / filename, job)
            selected = [s for s in steps if 'revision=$(cat scripts/android-ndk-version)' in s.get('run', '')]
            self.assertEqual(len(selected), 1)
            for success in (True, False):
                with self.subTest(workflow=filename, installer_success=success):
                    with tempfile.TemporaryDirectory(prefix='hermes-ndk-install-') as temp:
                        work = Path(temp)
                        (work / 'scripts').mkdir()
                        revision = (ROOT / 'scripts/android-ndk-version').read_text().strip()
                        (work / 'scripts/android-ndk-version').write_text(revision + '\n')
                        (work / 'build/native-evidence').mkdir(parents=True)
                        sdk = work / 'sdk'
                        cli = sdk / 'cmdline-tools/latest/bin/sdkmanager'
                        cli.parent.mkdir(parents=True)
                        cli.write_text('#!/bin/bash\nprintf "%s\n" "$@" > "$RECORD"\n'
                                       'if [ "$INSTALL_SUCCESS" != 1 ]; then echo controlled-install-failure >&2; exit 37; fi\n'
                                       'mkdir -p "$ANDROID_HOME/ndk/$PIN"\n'
                                       'printf "Pkg.Revision = %s\n" "$PIN" > "$ANDROID_HOME/ndk/$PIN/source.properties"\n')
                        cli.chmod(0o755)
                        record = work / 'invocation'
                        github_env = work / 'github-env'
                        github_env.touch()
                        environment = {'PATH': '/usr/bin:/bin', 'ANDROID_HOME': str(sdk),
                                       'ANDROID_SDK_ROOT': str(sdk), 'GITHUB_ENV': str(github_env),
                                       'RECORD': str(record), 'PIN': revision,
                                       'INSTALL_SUCCESS': '1' if success else '0'}
                        result = subprocess.run(['/bin/bash', '-e', '-c', selected[0]['run']],
                                                cwd=work, env=environment, capture_output=True, text=True,
                                                timeout=15)
                        called = record.exists()
                        created = (sdk / 'ndk' / revision / 'source.properties').exists()
                        exports = github_env.read_text()
                        print(f'NDK_INSTALL_CONTROL {filename} success={success} exit={result.returncode} '
                              f'called={called} created={created} exports={bool(exports)}', flush=True)
                        print(result.stdout, result.stderr, flush=True)
                        if success:
                            self.assertTrue(called, 'NDK_INSTALL_AVAILABLE_SDK_TOOL_NOT_CALLED')
                            self.assertEqual(record.read_text().splitlines(), ['ndk;' + revision])
                            self.assertEqual(result.returncode, 0)
                            self.assertTrue(created)
                            self.assertEqual(set(exports.splitlines()),
                                             {f'{name}={sdk}/ndk/{revision}' for name in ALIASES})
                        else:
                            self.assertNotEqual(result.returncode, 0,
                                                'NDK_INSTALL_FAILURE_MASKED_BY_TEE')
                            self.assertTrue(called)
                            self.assertFalse(created)
                            self.assertFalse(exports, 'failed installer exported NDK aliases')
                            self.assertIn('controlled-install-failure',
                                          (work / 'build/native-evidence/ndk-install.log').read_text())

    def test_ci_native_suite_gate(self):
        self.assert_gate('ci.yml', 'build')

    def test_release_native_suite_gate(self):
        steps = self.assert_gate('release.yml', 'release')
        publish = next(s for s in steps if 'gh release create' in s.get('run', ''))
        self.assertEqual(publish['if'], "success() && github.event_name == 'push'")
        self.assertTrue(any(s.get('name') == 'Check the signature' for s in steps))
        source = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertIn('  workflow_dispatch:', source)
        self.assertIn('    tags: ["v*"]', source)

    def test_reconnect_pinned_setup_and_diagnostics(self):
        steps = workflow_steps(ROOT / '.github/workflows/reconnect-stress.yml', 'stress')
        native = next(i for i, s in enumerate(steps) if s.get('run') == './scripts/build-tailnet.sh')
        setups = [i for i, s in enumerate(steps) if 'revision=$(cat scripts/android-ndk-version)' in s.get('run', '')]
        self.assertEqual(len(setups), 1, 'reconnect requires a pinned NDK install step')
        self.assertLess(setups[0], native)
        setup = steps[setups[0]]
        self.assertIn('revision=$(cat scripts/android-ndk-version)', setup['run'])
        self.assertIn('for variable in ' + ' '.join(ALIASES) + '; do', setup['run'])
        self.assertIn('echo "$variable=$ANDROID_HOME/ndk/$revision" >> "$GITHUB_ENV"', setup['run'])
        self.assertNotIn('if', setup)
        self.assertNotIn('continue-on-error', setup)
        diagnostics = [s for s in steps if s.get('name') == 'Retain native evidence']
        self.assertEqual(len(diagnostics), 1, 'retain native preflight diagnostics on either outcome')
        self.assertEqual(diagnostics[0]['if'], 'always()')
        self.assertTrue(diagnostics[0]['uses'].startswith('actions/upload-artifact@'))
        source = (ROOT / '.github/workflows/reconnect-stress.yml').read_text()
        self.assertIn('          path: build/native-evidence/', source)


if __name__ == '__main__':
    unittest.main()
