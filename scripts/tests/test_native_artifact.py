"""Hermetic parser fixtures use synthetic ELF headers, never built-library evidence."""
import importlib.util
import pathlib
import struct
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'validate-native-artifact.py'
spec = importlib.util.spec_from_file_location('native_guard', SCRIPT)
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
TAIL = 'jni/arm64-v8a/libgojni.so'

def elf(bits=64, machine=183, endian='<', **changes):
    """Small, clearly synthetic ET_DYN with one in-bounds LOAD."""
    size, phsize = (64, 56) if bits == 64 else (52, 32)
    ident = b'\x7fELF' + bytes([2 if bits == 64 else 1, 1 if endian == '<' else 2, 1]) + bytes(9)
    fields = dict(offset=0, vaddr=0, filesz=size+phsize, memsz=size+phsize, align=16384)
    fields.update(changes)
    x = fields
    if bits == 64:
        header = struct.pack(endian+'HHIQQQIHHHHHH', 3, machine, 1, 0, size, 0, 0, size, phsize, 1, 0, 0, 0)
        ph = struct.pack(endian+'IIQQQQQQ', 1, 5, x['offset'], x['vaddr'], 0, x['filesz'], x['memsz'], x['align'])
    else:
        header = struct.pack(endian+'HHIIIIIHHHHHH', 3, machine, 1, 0, size, 0, 0, size, phsize, 1, 0, 0, 0)
        ph = struct.pack(endian+'IIIIIIII', 1, x['offset'], x['vaddr'], 0, x['filesz'], x['memsz'], 5, x['align'])
    return ident+header+ph

class ArtifactFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.path = self.root/'test.aar'

    def archive(self, entries=None):
        with zipfile.ZipFile(self.path, 'w', compression=zipfile.ZIP_DEFLATED) as z:
            for name, data in (entries if entries is not None else [(TAIL, elf())]):
                z.writestr(name, data)
        return self.path

    def reject(self, text, path=None):
        with self.assertRaisesRegex(v.InvalidArtifact, text):
            v.inspect_artifact(path or self.path, {})


class ArtifactTests(ArtifactFixture):
    def test_valid_aar_and_compressed_native(self):
        self.archive()
        report = {}
        v.inspect_artifact(self.path, report)
        self.assertEqual(len(report['members']), 1)
        self.assertEqual(report['members'][0]['elf']['loads'][0]['align'], 16384)

    def test_missing_zero_empty_and_unsupported(self):
        self.reject('input.*regular file')
        self.path.write_bytes(b'')
        self.reject('ZIP')
        self.archive([])
        self.reject('native inventory')
        p = self.root/'input.so'; p.write_bytes(elf())
        self.reject('unsupported input', p)

    def test_tailnet_required_with_other_valid_native(self):
        self.archive([('jni/arm64-v8a/other.so', elf())])
        self.reject('required tailnet')

    def test_corrupt_truncated_and_duplicate_archive(self):
        self.path.write_bytes(b'PK\x03\x04broken')
        self.reject('ZIP')
        self.archive([(TAIL, elf()), (TAIL, elf())])
        self.reject('duplicate|ambiguous')
        self.archive(); self.path.write_bytes(self.path.read_bytes()[:-8])
        self.reject('ZIP')

    def test_every_load_alignment_and_congruence(self):
        for values, text in [({'align':4096}, 'LOAD.*alignment'), ({'align':24576}, 'LOAD.*alignment'), ({'vaddr':1}, 'LOAD.*congruence'), ({'align':32768,'vaddr':16384}, 'LOAD.*congruence'), ({'filesz':121}, 'LOAD.*bounds'), ({'memsz':119}, 'LOAD.*filesz')]:
            with self.subTest(values=values):
                self.archive([(TAIL,elf(**values))]); self.reject(text)

    def test_malformed_elf_and_table(self):
        valid = elf()
        zero = bytearray(valid); struct.pack_into('<H',zero,56,0)
        huge = bytearray(valid); struct.pack_into('<Q',huge,32,2**63)
        for data, text in [(b'not elf','ELF'),(valid[:63],'ELF.*header'),(valid[:-1],'program table'),(zero,'LOAD'),(huge,'program table')]:
            with self.subTest(text=text):
                self.archive([(TAIL,data)]); self.reject(text)

    def test_second_load_and_nonload_bounds(self):
        data=bytearray(elf())
        struct.pack_into('<H',data,56,2)
        data.extend(struct.pack('<IIQQQQQQ',1,4,0,0,0,0,0,4096))
        self.archive([(TAIL,data)]); self.reject('segment 1 LOAD.*alignment')
        data=bytearray(elf())
        data.extend(bytes(56)); struct.pack_into('<H',data,56,2)
        struct.pack_into('<IIQQQQQQ',data,120,4,4,0,0,0,1,0,4)
        self.archive([(TAIL,data)]); self.reject('segment 1.*filesz')

    def test_all_native_entries_abi_class_machine_byteorder(self):
        for data, name in [(elf(machine=62),TAIL),(elf(32,40),TAIL),(elf(endian='>'),TAIL),(elf(),'jni/unknown/extra.so'),(elf(),'lib/arm64-v8a/extra.so')]:
            with self.subTest(name=name,data=data[:20]):
                self.archive([(TAIL,elf()),(name if name != TAIL else 'jni/arm64-v8a/extra.so',data)])
                self.reject('ABI|byte order|native path')
        self.archive([(TAIL,elf()),('jni/armeabi-v7a/other.so',elf(32,40)),('jni/x86/other.so',elf(32,3)),('jni/x86_64/other.so',elf(64,62))])
        r={}; v.inspect_artifact(self.path,r); self.assertEqual(len(r['members']),4)

class PackagingTests(ArtifactFixture):
    def apk(self, aligned=True, compressed=False, descriptor=False):
        import io
        self.path = self.root/'test.apk'
        class Stream(io.BytesIO):
            def seekable(self): return False
            def seek(self, *args): raise OSError('stream fixture')
        stream = Stream() if descriptor else io.BytesIO()
        name = 'lib/arm64-v8a/libgojni.so'
        with zipfile.ZipFile(stream,'w') as z:
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
            if aligned:
                padding = (-30-len(name)-4) % 16384
                info.extra = struct.pack('<HH',0xffff,padding)+bytes(padding)
            z.writestr(info,elf())
        self.path.write_bytes(stream.getvalue())
        return self.path

    def test_apk_offset_and_uncompressed_contract(self):
        self.apk(aligned=False); self.reject('ZIP.*offset.*16384')
        self.apk(compressed=True); self.reject('ZIP.*uncompressed')
        self.apk(); r={}; v.inspect_artifact(self.path,r)
        self.assertEqual(r['members'][0]['data_offset'] % 16384,0)
        self.apk(descriptor=True); v.inspect_artifact(self.path,{})

    def test_local_descriptor_and_crc_consistency(self):
        self.apk(); good=self.path.read_bytes()
        for offset, value, diagnostic in [(8,8,'central/local'), (30,ord('x'),'filename'), (16384,0,'CRC')]:
            with self.subTest(diagnostic=diagnostic):
                data=bytearray(good); data[offset]=value; self.path.write_bytes(data); self.reject(diagnostic)
        self.apk(descriptor=True); data=bytearray(self.path.read_bytes()); data[16384+len(elf())+4]^=1
        self.path.write_bytes(data); self.reject('descriptor')

    def test_android_raw_zero_local_alignment_padding(self):
        self.apk()
        data=bytearray(self.path.read_bytes())
        n,x=struct.unpack_from('<HH',data,26)
        data[30+n:30+n+x]=bytes(x)
        self.path.write_bytes(data)
        r={}; v.inspect_artifact(self.path,r)
        self.assertEqual(r['members'][0]['data_offset'],16384)

    def test_ignored_member_crc_and_finite_budgets(self):
        from unittest.mock import patch
        self.archive([(TAIL,elf()),('ignored.txt',b'x'*512)])
        for key,limit,text in [('MAX_ARCHIVE',32,'budget|size'),('MAX_MEMBER',256,'budget'),('MAX_TOTAL',512,'aggregate'),('MAX_CENTRAL',16,'budget'),('MAX_MEMBERS',1,'budget'),('MAX_PH',0,'program table'),('MAX_NATIVE',0,'budget')]:
            with self.subTest(key=key), patch.object(v,key,limit): self.reject(text)
        with zipfile.ZipFile(self.path,'w') as z:
            z.writestr(TAIL,elf()); z.writestr('ignored',b'abc')
        with zipfile.ZipFile(self.path) as z: i=z.getinfo('ignored'); off=i.header_offset+30+len(i.filename)
        data=bytearray(self.path.read_bytes()); data[off]^=1; self.path.write_bytes(data); self.reject('CRC')

    def test_ambiguous_names_and_unsupported_zip(self):
        for name in ['../bad','/bad','a//bad','a/./bad','a\\bad','a/../bad','a\x00bad','C:bad']:
            with self.subTest(name=name):
                # NUL is patched after writing because zipfile normalizes it itself.
                written = name.replace('\x00','X')
                self.archive([(TAIL,elf()),(written,b'x')])
                if '\x00' in name: self.path.write_bytes(self.path.read_bytes().replace(written.encode(),name.encode()))
                self.reject('ambiguous|traversal')
        self.archive([(TAIL,elf()),(TAIL.upper(),elf())]); self.reject('duplicate|ambiguous')
        self.archive(); data=bytearray(self.path.read_bytes()); struct.pack_into('<H',data,len(data)-12,65535)
        self.path.write_bytes(data); self.reject('ZIP64|split')

    def test_file_directory_alias_is_ambiguous(self):
        for entries in [[('assets',b'x'),('assets/file',b'x')],[('assets/file',b'x'),('ASSETS',b'x')]]:
            self.archive([(TAIL,elf()),*entries]); self.reject('ambiguous')

    def test_member_path_budgets_precede_prefix_allocation(self):
        for name in ['a'*1025, '/'.join(['a']*33)]:
            self.archive([(TAIL,elf()),(name,b'x')]); self.reject('path budget')

    def cli(self, *args):
        import subprocess, sys
        return subprocess.run([sys.executable,str(SCRIPT),str(self.path),*map(str,args)],capture_output=True,text=True)

    def test_report_never_clobbers_input_symlink_hardlink_or_existing(self):
        import os
        self.archive(); original=self.path.read_bytes()
        for name,kind in [('symlink','symlink'),('hardlink','hardlink'),('existing.json','file')]:
            dest=self.root/name
            if kind=='symlink': dest.symlink_to(self.path)
            elif kind=='hardlink': os.link(self.path,dest)
            else: dest.write_text('original')
            result=self.cli('--report',dest)
            self.assertNotEqual(result.returncode,0,result.stdout)
            self.assertEqual(self.path.read_bytes(),original)
            if kind=='file': self.assertEqual(dest.read_text(),'original')
        result=self.cli('--report',self.path)
        self.assertNotEqual(result.returncode,0); self.assertEqual(self.path.read_bytes(),original)
        result=self.cli('--report',self.root/'missing'/'report.json')
        self.assertNotEqual(result.returncode,0)

    def test_cli_report_and_zipalign_failure_are_nonzero(self):
        import json
        self.archive([(TAIL,elf(align=4096))]); report=self.root/'fail.json'
        result=self.cli('--report',report)
        self.assertNotEqual(result.returncode,0); self.assertIn('LOAD',json.loads(report.read_text())['error'])
        self.apk(); result=self.cli()
        self.assertNotEqual(result.returncode,0); self.assertIn('zipalign',result.stdout)
        fake=self.root/'zipalign'; fake.write_text('#!/bin/sh\necho unit-test-only-zipalign-failure\nexit 7\n'); fake.chmod(0o755)
        report=self.root/'zipfail.json'; result=self.cli('--zipalign',fake,'--report',report)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(json.loads(report.read_text())['zipalign']['exit'],7)


if __name__ == '__main__':
    unittest.main()
