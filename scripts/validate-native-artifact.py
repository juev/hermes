#!/usr/bin/env python3
"""Fail closed on Android AAR/APK ELF and 16 KiB native packaging defects.

No archive paths are extracted. ZIP64 and encrypted archives are unsupported.
Reports are new files; an existing destination is never overwritten.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

PAGE = 16384
MAX_ARCHIVE = 512 * 1024 * 1024
MAX_MEMBER = 256 * 1024 * 1024
MAX_TOTAL = 1024 * 1024 * 1024
MAX_CENTRAL = 16 * 1024 * 1024
MAX_MEMBERS = 20000
MAX_NATIVE = 512
MAX_PH = 4096
MAX_PATH = 1024
MAX_DEPTH = 32
ABIS = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40),
        'x86': (1, 3), 'x86_64': (2, 62)}


class InvalidArtifact(ValueError):
    pass


def require(ok, message):
    if not ok:
        raise InvalidArtifact(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inspect_elf(data, abi):
    require(len(data) >= 16 and data[:4] == b'\x7fELF', 'ELF identity/header invalid')
    cls, order, version = data[4:7]
    require(cls in (1, 2) and order in (1, 2) and version == 1, 'ELF class/byte order/version unsupported')
    endian = '<' if order == 1 else '>'
    ehsize, phsize = (64, 56) if cls == 2 else (52, 32)
    require(len(data) >= ehsize, 'ELF header truncated')
    fmt = endian + ('HHIQQQIHHHHHH' if cls == 2 else 'HHIIIIIHHHHHH')
    h = struct.unpack_from(fmt, data, 16)
    kind, machine, eversion, _, phoff, _, _, declared_ehsize, entsize, count, _, _, _ = h
    require(kind == 3 and eversion == 1 and declared_ehsize == ehsize, 'ELF header type/version/size invalid')
    require(abi in ABIS and (cls, machine) == ABIS[abi], f'ELF ABI/machine mismatch: {abi}, class={cls}, machine={machine}')
    require(order == 1, 'ELF ABI byte order unsupported: Android requires little endian')
    require(0 < count <= MAX_PH and entsize == phsize and phoff >= ehsize
            and phoff <= len(data) and count <= (len(data)-phoff)//phsize,
            'ELF program table invalid/truncated or no LOAD headers')
    result = dict(bits=32 if cls == 1 else 64, byte_order='little', machine=machine, loads=[], program_headers=[])
    for i in range(count):
        p = struct.unpack_from(endian+('IIQQQQQQ' if cls == 2 else 'IIIIIIII'), data, phoff+i*phsize)
        if cls == 2:
            typ, flags, offset, vaddr, paddr, filesz, memsz, align = p
        else:
            typ, offset, vaddr, paddr, filesz, memsz, flags, align = p
        segment = dict(index=i, type=typ, flags=flags, offset=offset, vaddr=vaddr,
                       paddr=paddr, filesz=filesz, memsz=memsz, align=align)
        result['program_headers'].append(segment)
        label = f'ELF segment {i}' + (' LOAD' if typ == 1 else '')
        require(offset <= len(data) and filesz <= len(data)-offset, f'{label} file bounds invalid')
        require(filesz <= memsz, f'{label} filesz exceeds memsz')
        if typ != 1:
            continue
        result['loads'].append(segment)
        require(align >= PAGE and align & (align-1) == 0, f'{label} alignment {align} is not a power of two >= {PAGE}')
        require(offset % align == vaddr % align and offset % PAGE == vaddr % PAGE,
                f'{label} offset/vaddr congruence invalid (alignment {align})')
    require(result['loads'], 'ELF has zero LOAD segments')
    return result


def extra_fields(data, local_padding=False):
    pos = 0
    padding_start = len(data.rstrip(b'\0'))
    while pos < len(data):
        # Android's APK writer/zipalign may fill local extra space with raw
        # zeros, including fewer than four trailing bytes. Central extras
        # still require structured fields.
        if local_padding and pos >= padding_start:
            return
        require(len(data)-pos >= 4, 'ZIP extra field truncated')
        tag, size = struct.unpack_from('<HH', data, pos)
        pos += 4
        require(size <= len(data)-pos, 'ZIP extra field bounds invalid')
        require(tag != 1, 'ZIP64 unsupported')
        pos += size


def central_bounds(raw, size):
    require(22 <= size <= MAX_ARCHIVE, 'ZIP file size invalid or archive budget exceeded')
    raw.seek(max(0, size-65557))
    tail = raw.read(65557)
    at = tail.rfind(b'PK\x05\x06')
    require(at >= 0 and len(tail)-at >= 22, 'ZIP end record missing/truncated')
    _, disk, cd_disk, disk_count, count, length, offset, comment = struct.unpack_from('<4s4H2IH', tail, at)
    require(at+22+comment == len(tail), 'ZIP end record/trailing bytes invalid')
    require(disk == cd_disk == 0 and disk_count == count, 'ZIP split archive unsupported')
    require(count != 65535 and length != 0xffffffff and offset != 0xffffffff, 'ZIP64 unsupported')
    end = size-len(tail)+at
    require(count <= MAX_MEMBERS and length <= MAX_CENTRAL, 'ZIP central header/member budget exceeded')
    require(offset <= end and length == end-offset, 'ZIP central directory bounds invalid (ZIP64 unsupported)')
    # Bound zipfile's allocations before it parses central headers.
    raw.seek(offset)
    for _ in range(count):
        h = raw.read(46)
        require(len(h) == 46 and h[:4] == b'PK\x01\x02', 'ZIP central header invalid')
        n, x, c = struct.unpack_from('<HHH', h, 28)
        require(raw.tell()+n+x+c <= end, 'ZIP central header bounds invalid')
        raw.seek(n+x+c, 1)
    require(raw.tell() == end, 'ZIP central header count inconsistent')
    return offset, count


def local_record(raw, info, central):
    offset = info.header_offset
    require(0 <= offset <= central-30, f'{info.filename}: ZIP local header bounds invalid')
    raw.seek(offset)
    h = raw.read(30)
    sig, version, flags, method, mtime, mdate, crc, packed, size, n, x = struct.unpack('<4s5H3I2H', h)
    require(sig == b'PK\x03\x04', f'{info.filename}: ZIP local header signature invalid')
    require(flags == info.flag_bits and method == info.compress_type and version == info.extract_version,
            f'{info.filename}: ZIP central/local flags/method/version mismatch')
    require(not flags & ~0x808, f'{info.filename}: ZIP encryption/flags unsupported')
    require(packed != 0xffffffff and size != 0xffffffff, f'{info.filename}: ZIP64 unsupported')
    data_offset = offset+30+n+x
    require(data_offset <= central and info.compress_size <= central-data_offset, f'{info.filename}: ZIP local data bounds invalid')
    name = raw.read(n)
    require(name == info.orig_filename.encode('utf-8' if flags & 0x800 else 'cp437'), f'{info.filename}: ZIP central/local filename mismatch')
    extra_fields(raw.read(x), local_padding=True)
    expected = (info.CRC, info.compress_size, info.file_size)
    actual = (crc, packed, size)
    end = data_offset+info.compress_size
    if flags & 8:
        require(all(a in (0, e) for a, e in zip(actual, expected)), f'{info.filename}: ZIP descriptor local sizes mismatch')
        raw.seek(end)
        first = raw.read(4)
        if first == b'PK\x07\x08':
            desc = raw.read(12); end += 16
        else:
            desc = first+raw.read(8); end += 12
        require(end <= central and len(desc) == 12 and struct.unpack('<III', desc) == expected,
                f'{info.filename}: ZIP data descriptor mismatch/truncated (ZIP64 unsupported)')
    else:
        require(actual == expected, f'{info.filename}: ZIP central/local CRC/size mismatch')
    return data_offset, end


def member_bytes(raw, info, data_offset, native, total):
    """Stream every member, including ignored files, with measured limits and CRC.

    zipfile alone stops at declared file_size; inspect the full deflate stream
    too, so understated sizes cannot hide extra decompressed data.
    """
    raw.seek(data_offset)
    remaining, measured, crc, chunks = info.compress_size, 0, 0, []
    inflater = zlib.decompressobj(-15) if info.compress_type == zipfile.ZIP_DEFLATED else None
    while remaining:
        packed = raw.read(min(65536, remaining))
        require(packed, f'{info.filename}: ZIP compressed data truncated')
        remaining -= len(packed)
        while packed:
            if inflater:
                chunk = inflater.decompress(packed, 1024*1024)
                packed = inflater.unconsumed_tail
                require(not inflater.unused_data, f'{info.filename}: ZIP trailing deflate data')
            else:
                chunk, packed = packed, b''
            measured += len(chunk); total += len(chunk)
            require(measured <= MAX_MEMBER and total <= MAX_TOTAL,
                    f'{info.filename}: ZIP measured decompression budget exceeded')
            require(measured <= info.file_size, f'{info.filename}: ZIP decompressed size inconsistent')
            crc = zlib.crc32(chunk, crc)
            if native:
                chunks.append(chunk)
    require(inflater is None or inflater.eof, f'{info.filename}: ZIP deflate stream truncated')
    require(measured == info.file_size, f'{info.filename}: ZIP decompressed size inconsistent')
    require(crc == info.CRC, f'{info.filename}: ZIP CRC mismatch')
    return b''.join(chunks), total


def inspect_artifact(path, report):
    path = Path(path)
    require(path.suffix.lower() in ('.aar', '.apk'), 'unsupported input: expected .aar or .apk')
    require(path.is_file(), 'input must be an existing regular file')
    with path.open('rb') as raw:
        metadata = os.fstat(raw.fileno())
        require(stat.S_ISREG(metadata.st_mode), 'input must be a regular file')
        central, count = central_bounds(raw, metadata.st_size)
        raw.seek(0)
        hasher = hashlib.sha256()
        for chunk in iter(lambda: raw.read(1024*1024), b''):
            hasher.update(chunk)
        report.update(artifact=str(path), bytes=metadata.st_size, sha256=hasher.hexdigest(),
                      kind=path.suffix.lower()[1:], members=[], archive_members=[])
        with zipfile.ZipFile(raw) as archive:
            infos = archive.infolist()
            require(len(infos) == count, 'ZIP member count inconsistent')
            require(sum(i.file_size for i in infos) <= MAX_TOTAL, 'ZIP aggregate decompression budget exceeded')
            seen, files, parents, intervals, total, native_count = set(), set(), set(), [], 0, 0
            for info in infos:
                name = info.orig_filename
                require(len(name) <= MAX_PATH, 'ZIP member path budget exceeded')
                parts = name.rstrip('/').split('/')
                require(len(parts) <= MAX_DEPTH, 'ZIP member path budget exceeded')
                require(name and name == info.filename and not name.startswith('/')
                        and '\\' not in name and not any(ord(c) < 32 or ord(c) == 127 for c in name)
                        and all(p not in ('', '.', '..') and ':' not in p for p in parts),
                        f'ZIP ambiguous/traversal member: {name!r}')
                key = name.rstrip('/').casefold()
                require(key not in seen, f'ZIP duplicate/ambiguous member: {name!r}')
                seen.add(key)
                prefixes = {'/'.join(parts[:i]).casefold() for i in range(1, len(parts))}
                require(not prefixes & files and (name.endswith('/') or key not in parents),
                        f'ZIP ambiguous file/directory member: {name!r}')
                parents.update(prefixes)
                if not name.endswith('/'):
                    files.add(key)
                mode = info.external_attr >> 16
                require(stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR), f'ZIP unsupported member type: {name}')
                require(0 <= info.file_size <= MAX_MEMBER and 0 <= info.compress_size <= MAX_ARCHIVE,
                        f'{name}: ZIP member decompression budget exceeded')
                require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), f'{name}: ZIP compression unsupported')
                extra_fields(info.extra)
                data_offset, end = local_record(raw, info, central)
                intervals.append((info.header_offset, end))
                native = name.lower().endswith('.so')
                entry = dict(name=name, bytes=info.file_size, compressed_bytes=info.compress_size,
                             compression=info.compress_type, data_offset=data_offset)
                report['archive_members'].append(entry)
                if native:
                    native_count += 1
                    require(native_count <= MAX_NATIVE, 'native member budget exceeded')
                    prefix = 'jni' if path.suffix.lower() == '.aar' else 'lib'
                    match = re.fullmatch(prefix+r'/([^/]+)/[^/]+\.so', name)
                    require(match is not None, f'{name}: unexpected native path')
                    abi = match[1]
                    require(abi in ABIS, f'{name}: unsupported ABI {abi}')
                data, total = member_bytes(raw, info, data_offset, native, total)
                if native:
                    entry.update(sha256=digest(data), abi=abi)
                    report['members'].append(entry)
                    try:
                        entry['elf'] = inspect_elf(data, abi)
                    except InvalidArtifact as error:
                        raise InvalidArtifact(f'{name}: {error}') from error
                    if path.suffix.lower() == '.apk':
                        require(info.compress_type == zipfile.ZIP_STORED, f'{name}: APK ZIP native member must be uncompressed')
                        require(data_offset % PAGE == 0, f'{name}: APK ZIP data offset {data_offset} is not divisible by {PAGE}')
            for left, right in zip(sorted(intervals), sorted(intervals)[1:]):
                require(left[1] <= right[0], 'ZIP overlapping/ambiguous local members')
            require(native_count > 0, 'empty native inventory')
            expected = ('jni' if path.suffix.lower() == '.aar' else 'lib')+'/arm64-v8a/libgojni.so'
            require(any(m['name'] == expected for m in report['members']), f'missing required tailnet member {expected}')


def check_zipalign(tool, artifact):
    require(tool is not None, 'APK requires --zipalign executable for independent check')
    path = Path(tool).resolve(strict=True)
    require(path.is_file() and os.access(path, os.X_OK), 'zipalign must be an executable regular file')
    cmd = [str(path), '-c', '-P', '16', '-v', '4', str(artifact)]
    with tempfile.TemporaryFile() as output:
        proc = subprocess.run(cmd, stdout=output, stderr=subprocess.STDOUT, timeout=120)
        require(output.tell() <= 4*1024*1024, 'zipalign output budget exceeded')
        output.seek(0)
        return dict(argv=cmd, tool_sha256=digest(path.read_bytes()), exit=proc.returncode,
                    output=output.read().decode('utf-8', errors='replace'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact')
    parser.add_argument('--report', help='new JSON report path (must not already exist)')
    parser.add_argument('--zipalign', help='selected executable; required for APKs')
    args = parser.parse_args()
    report = dict(passed=False)
    out = None
    if args.report:
        try:
            # O_EXCL rejects all existing destinations, including input aliases;
            # opening before validation also prevents later tool output collisions.
            fd = os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
            out = os.fdopen(fd, 'w')
        except OSError as error:
            print(f'FAIL: report must be a new file: {error}', file=sys.stderr)
            return 1
    try:
        inspect_artifact(args.artifact, report)
        report['passed'] = True
    except (InvalidArtifact, OSError, ValueError, EOFError, struct.error, zipfile.BadZipFile, zlib.error, NotImplementedError, RuntimeError) as error:
        report['error'] = str(error)
    if Path(args.artifact).suffix.lower() == '.apk':
        try:
            # Run independently even when ELF/ZIP inspection failed.
            report['zipalign'] = check_zipalign(args.zipalign, args.artifact)
            require(report['zipalign']['exit'] == 0, f"zipalign failed with exit {report['zipalign']['exit']}")
        except (InvalidArtifact, OSError, subprocess.SubprocessError) as error:
            report['passed'] = False
            report['error'] = report.get('error', '') + ('; ' if 'error' in report else '') + str(error)
    if out:
        try:
            with out:
                json.dump(report, out, indent=2)
                out.write('\n')
        except OSError as error:
            report['passed'] = False
            report['error'] = f'report write failed: {error}'
    print(('PASS' if report['passed'] else 'FAIL')+': '+args.artifact+(': '+report['error'] if 'error' in report else ''))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
