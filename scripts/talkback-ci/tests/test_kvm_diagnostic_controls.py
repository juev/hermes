"""Synthetic kernel interface diagnostics using real owned harmless descriptors."""
import errno
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import kvm_gate as gate

class DiagnosticControls(unittest.TestCase):
    def test_synthetic_stages_cleanup_and_positive(self):
        scenarios = ["open_device", "fstat_device", "device_type", "api_ioctl", "api_version", "create_vm", "close_vm", "close_device", "primary_and_close", "success"]
        for scenario in scenarios:
            with self.subTest(scenario=scenario):
                real_open, real_close, real_stat = os.open, os.close, os.fstat
                device = real_open("/dev/null", os.O_RDWR)
                vm = real_open("/dev/null", os.O_RDWR)
                closed = []
                def opening(*args):
                    if scenario == "open_device": raise OSError(errno.EACCES, "private synthetic diagnostic")
                    return device
                def stating(fd):
                    if scenario == "fstat_device": raise OSError(errno.EIO, "private synthetic diagnostic")
                    if scenario == "device_type":
                        class Regular: st_mode = 0o100600
                        return Regular()
                    return real_stat(fd)
                def ioctl(fd, request, arg):
                    if request == gate.KVM_GET_API_VERSION:
                        if scenario in ("api_ioctl", "primary_and_close"): raise OSError(errno.ENOTTY, "private synthetic diagnostic")
                        return 11 if scenario == "api_version" else 12
                    if scenario == "create_vm": raise OSError(errno.ENOMEM, "private synthetic diagnostic")
                    return vm
                def closing(fd):
                    real_close(fd); closed.append(fd)
                    if (fd == vm and scenario == "close_vm") or (fd == device and scenario in ("close_device", "primary_and_close")):
                        raise OSError(errno.EIO, "private synthetic diagnostic")
                try:
                    with patch.object(gate.os, "open", side_effect=opening), patch.object(gate.os, "fstat", side_effect=stating), patch.object(gate.fcntl, "ioctl", side_effect=ioctl), patch.object(gate.os, "close", side_effect=closing):
                        if scenario == "success":
                            self.assertEqual(gate.require_kvm("synthetic"), {"api_version":12,"vm_creation_observed":True})
                            self.assertEqual(closed, [vm,device]); continue
                        with self.assertRaises(gate.KvmUnavailable) as caught: gate.require_kvm("synthetic")
                    record = caught.exception.diagnostic
                    self.assertNotIn("private", str(record))
                    if scenario in ("close_vm", "close_device"):
                        self.assertIsNone(record["primary"])
                        self.assertEqual(record["api_version"], 12)
                        self.assertTrue(record["vm_creation_observed"])
                        self.assertEqual(closed, [vm,device])
                        self.assertEqual(record["cleanup"]["vm" if scenario == "close_vm" else "device"], {"status":"failed","errno":errno.EIO})
                    else:
                        stage = "api_ioctl" if scenario == "primary_and_close" else scenario
                        self.assertEqual(record["primary"]["stage"], stage)
                        expected = {"open_device":errno.EACCES,"fstat_device":errno.EIO,"device_type":None,"api_ioctl":errno.ENOTTY,"api_version":None,"create_vm":errno.ENOMEM}
                        self.assertEqual(record["primary"]["errno"], expected[stage])
                        self.assertFalse(record["vm_creation_observed"])
                        if scenario != "open_device": self.assertIn(device,closed)
                        if scenario == "primary_and_close": self.assertEqual(record["cleanup"]["device"], {"status":"failed","errno":errno.EIO})
                finally:
                    for fd in (vm,device):
                        if fd not in closed: real_close(fd)
