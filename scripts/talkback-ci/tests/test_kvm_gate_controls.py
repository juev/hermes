"""Interface controls with synthetic kernel replies, never real KVM/guest success."""
import errno
import fcntl
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import kvm_gate as gate


class KvmInterfaceControls(unittest.TestCase):
    def assert_closed(self, fd):
        with self.assertRaises(OSError) as caught:
            fcntl.fcntl(fd, fcntl.F_GETFD)
        self.assertEqual(caught.exception.errno, errno.EBADF)

    def test_missing_and_regular_owned_paths_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            regular = Path(directory) / "regular"
            regular.write_text("harmless")
            real_open = os.open
            opened = []
            def recording_open(*args):
                fd = real_open(*args)
                opened.append(fd)
                return fd
            for path in [regular, Path(directory) / "absent"]:
                with self.subTest(kind=path.name):
                    with patch.object(gate.os, "open", side_effect=recording_open):
                        with self.assertRaises(gate.KvmUnavailable):
                            gate.require_kvm(path)
            for fd in opened:
                self.assert_closed(fd)

    def interface(self, replies, success=False):
        real_open = os.open
        opened = []
        def recording_open(*args):
            self.assertTrue(args[1] & os.O_RDWR)
            self.assertTrue(args[1] & os.O_CLOEXEC)
            fd = real_open(*args)
            opened.append(fd)
            return fd
        with patch.object(gate.os, "open", side_effect=recording_open), patch.object(gate.fcntl, "ioctl", side_effect=replies) as ioctl:
            if success:
                proof = gate.require_kvm("/dev/null")
                self.assertEqual(proof, {"api_version": 12, "vm_creation_observed": True})
            else:
                with self.assertRaises(gate.KvmUnavailable):
                    gate.require_kvm("/dev/null")
            self.assertEqual(ioctl.call_args_list[0].args[1:], (gate.KVM_GET_API_VERSION, 0))
            if len(ioctl.call_args_list) > 1:
                self.assertEqual(ioctl.call_args_list[1].args[1:], (gate.KVM_CREATE_VM, 0))
        for fd in opened:
            self.assert_closed(fd)

    def test_synthetic_api12_vmfd_admission_closes_both(self):
        vm = os.open("/dev/null", os.O_RDWR | os.O_CLOEXEC)
        try:
            self.interface([12, vm], success=True)
            self.assert_closed(vm)
        finally:
            try:
                os.close(vm)
            except OSError as error:
                if error.errno != errno.EBADF:
                    raise

    def test_synthetic_wrong_api_refused_and_device_closed(self):
        self.interface([11])

    def test_synthetic_api_ioctl_failure_closes_device(self):
        self.interface([OSError(errno.ENOTTY, "synthetic unsupported ioctl")])

    def test_synthetic_vm_creation_failure_closes_device(self):
        self.interface([12, OSError(errno.EACCES, "synthetic VM refusal")])

    def test_synthetic_inaccessible_open_translated(self):
        with patch.object(gate.os, "open", side_effect=PermissionError(errno.EACCES, "synthetic refusal")):
            with self.assertRaises(gate.KvmUnavailable):
                gate.require_kvm("owned-interface-path")
