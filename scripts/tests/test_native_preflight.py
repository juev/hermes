"""Stubs prove preflight refusal only, never native compilation."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
REV='28.2.13676358'

class PreflightTests(unittest.TestCase):
    def test_native_generation_barrier(self):
        with tempfile.TemporaryDirectory() as tmp:
            r=Path(tmp); sdk=r/'sdk'; ndk=sdk/'ndk'/REV; tools=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
            tools.mkdir(parents=True)
            stub=r/'bin'; stub.mkdir(); marker=r/'go-called'
            go=stub/'go'; go.write_text('#!/bin/sh\ntouch "$BARRIER_MARKER"\necho PREFLIGHT_BARRIER >&2\nexit 91\n'); go.chmod(0o755)
            env={k:v for k,v in os.environ.items() if k not in ['ANDROID_NDK_HOME','ANDROID_NDK_ROOT','NDK_HOME','NDK_ROOT']}
            evidence=r/'evidence'
            env.update(ANDROID_HOME=str(sdk),PATH=str(stub)+':'+env['PATH'],BARRIER_MARKER=str(marker),NATIVE_EVIDENCE_DIR=str(evidence))
            props=ndk/'source.properties'
            (r/'other').mkdir()
            cases=[('missing revision',None,{},'source.properties'),('wrong revision','27.3.13750724',{},'revision'),('missing tools',REV,{},'executable'),('conflicting override',REV,{'ANDROID_NDK_HOME':str(r/'other')},'conflict'),('conflicting root',REV,{'ANDROID_NDK_ROOT':str(r/'other')},'conflict')]
            for label,rev,overrides,text in cases:
                with self.subTest(label=label):
                    if props.exists(): props.unlink()
                    if marker.exists(): marker.unlink()
                    if rev: props.write_text('Pkg.Revision = '+rev+'\n')
                    p=subprocess.run(['bash',str(ROOT/'scripts/build-tailnet.sh')],env=env|overrides,capture_output=True,text=True,cwd=ROOT)
                    self.assertNotEqual(p.returncode,0)
                    self.assertIn(text,p.stdout+p.stderr)
                    self.assertFalse(marker.exists(),'Go/native barrier reached during rejected preflight')
                    runs=list(evidence.glob('tailnet.*/exit.txt'))
                    self.assertTrue(runs,'failed preflight must retain a status report')
                    self.assertEqual(max(runs,key=lambda x:x.stat().st_mtime_ns).read_text().strip(),str(p.returncode))
            props.write_text('Pkg.Revision = '+REV+'\n')
            for name in ['clang','ld.lld','aarch64-linux-android31-clang','llvm-ar','llvm-strip']:
                p=tools/name; p.write_text('#!/bin/sh\nexit 0\n'); p.chmod(0o755)
            p=subprocess.run(['bash',str(ROOT/'scripts/build-tailnet.sh')],env=env,capture_output=True,text=True,cwd=ROOT)
            self.assertEqual(p.returncode,91,p.stdout+p.stderr)
            self.assertTrue(marker.exists())

if __name__=='__main__': unittest.main()
