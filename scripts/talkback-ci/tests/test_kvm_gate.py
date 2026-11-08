import errno
import fcntl
import json
import os
from pathlib import Path
import platform
import stat
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kvm_gate import KvmUnavailable, require_kvm


class KvmAdmissionTest(unittest.TestCase):
    def test_non_kvm_character_device_is_rejected(self):
        self.assertEqual(platform.system(), "Linux")
        self.assertTrue(stat.S_ISCHR(os.stat("/dev/null").st_mode))
        fd = os.open("/dev/null", os.O_RDWR)
        calibration_errno = None
        try:
            try:
                fcntl.ioctl(fd, 0xAE00, 0)  # KVM_GET_API_VERSION
            except OSError as error:
                calibration_errno = error.errno
        finally:
            os.close(fd)
        self.assertEqual(calibration_errno, errno.ENOTTY)
        print(json.dumps({"fixture": "/dev/null", "character_device": True,
                          "KVM_GET_API_VERSION_errno": calibration_errno,
                          "calibration_fd_closed": True}), flush=True)
        with self.assertRaises(KvmUnavailable, msg="KVM_NON_KVM_CHARACTER_DEVICE_ADMITTED"):
            require_kvm("/dev/null")
