"""Hardware readiness through the independently checked Linux KVM gate."""
import argparse
import json
from kvm_gate import KvmUnavailable, require_kvm


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="/dev/kvm")
    args = parser.parse_args(argv)
    try:
        proof = require_kvm(args.device)
    except KvmUnavailable:
        print(json.dumps({"status": "KVM_UNAVAILABLE", "reason": "kvm_unavailable"}))
        return 2
    print(json.dumps({"status": "KVM_READY", "api_version": proof["api_version"],
                      "vm_creation_observed": proof["vm_creation_observed"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
