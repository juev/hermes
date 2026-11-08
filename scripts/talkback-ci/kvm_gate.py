"""Fail-closed Linux KVM admission with finite resource diagnostics."""
import fcntl
import os
import stat
KVM_GET_API_VERSION = 0xAE00
KVM_CREATE_VM = 0xAE01
KVM_API_VERSION = 12

class KvmUnavailable(RuntimeError):
    def __init__(self, message, diagnostic=None):
        super().__init__(message)
        self.diagnostic = diagnostic


def require_kvm(device_path):
    record = {"primary": None, "api_version": None, "vm_creation_observed": False,
              "cleanup": {name: {"status": "not_acquired", "errno": None} for name in ("vm", "device")}}
    device = vm = None
    stage = "open_device"
    try:
        device = os.open(device_path, os.O_RDWR | os.O_CLOEXEC)
        stage = "fstat_device"
        mode = os.fstat(device).st_mode
        stage = "device_type"
        if not stat.S_ISCHR(mode):
            raise KvmUnavailable("KVM path is not a character device")
        stage = "api_ioctl"
        version = fcntl.ioctl(device, KVM_GET_API_VERSION, 0)
        record["api_version"] = version
        stage = "api_version"
        if version != KVM_API_VERSION:
            raise KvmUnavailable("Unsupported KVM API version")
        stage = "create_vm"
        vm = fcntl.ioctl(device, KVM_CREATE_VM, 0)
        record["vm_creation_observed"] = True
    except (OSError, KvmUnavailable) as error:
        record["primary"] = {"stage": stage, "errno": error.errno if isinstance(error, OSError) else None}
    finally:
        try:
            if vm is not None:
                try:
                    os.close(vm)
                    record["cleanup"]["vm"]["status"] = "closed"
                except OSError as error:
                    record["cleanup"]["vm"] = {"status": "failed", "errno": error.errno}
        finally:
            if device is not None:
                try:
                    os.close(device)
                    record["cleanup"]["device"]["status"] = "closed"
                except OSError as error:
                    record["cleanup"]["device"] = {"status": "failed", "errno": error.errno}
    if record["primary"] is not None or any(item["status"] == "failed" for item in record["cleanup"].values()):
        raise KvmUnavailable("KVM admission unavailable", record)
    return {"api_version": record["api_version"], "vm_creation_observed": record["vm_creation_observed"]}
