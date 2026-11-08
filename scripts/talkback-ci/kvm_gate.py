"""Fail-closed Linux KVM admission; descriptors never escape this check."""
import fcntl
import os
import stat

# Linux UAPI linux/kvm.h: _IO(0xAE, 0x00/0x01), API version 12.
KVM_GET_API_VERSION = 0xAE00
KVM_CREATE_VM = 0xAE01
KVM_API_VERSION = 12


class KvmUnavailable(RuntimeError):
    pass


def require_kvm(device_path):
    try:
        device = os.open(device_path, os.O_RDWR | os.O_CLOEXEC)
        try:
            if not stat.S_ISCHR(os.fstat(device).st_mode):
                raise KvmUnavailable("KVM path is not a character device")
            version = fcntl.ioctl(device, KVM_GET_API_VERSION, 0)
            if version != KVM_API_VERSION:
                raise KvmUnavailable("Unsupported KVM API version")
            vm = fcntl.ioctl(device, KVM_CREATE_VM, 0)
            try:
                return {"api_version": version, "vm_creation_observed": True}
            finally:
                os.close(vm)
        finally:
            os.close(device)
    except OSError as error:
        raise KvmUnavailable("KVM device or VM creation unavailable") from error
