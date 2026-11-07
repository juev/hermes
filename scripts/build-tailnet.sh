#!/usr/bin/env bash
# Builds app/libs/tailnet.aar, the userspace Tailscale node of the app, with gomobile.
# Needs Go, the Android SDK and its NDK; arm64 only, the phones the app targets.
set -euo pipefail

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
export GOTOOLCHAIN=auto
evidence=${NATIVE_EVIDENCE_DIR:-$root/build/native-evidence}
mkdir -p "$evidence"
run_evidence=$(mktemp -d "$evidence/tailnet.XXXXXX")
tools=
finish() {
    status=$?
    printf '%s\n' "$status" > "$run_evidence/exit.txt" || :
    if [[ -n "$tools" ]]; then rm -rf "$tools"; fi
    exit "$status"
}
trap finish EXIT
fail() { echo "tailnet: $*" | tee "$run_evidence/failure.txt" >&2; exit 1; }
# gomobile compiles its Java glue with javac; the macOS /usr/bin/java stub has none behind it.
case "$(uname -s)" in
    Darwin)
        export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21)}
        export ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
        host=darwin-x86_64 ;;
    Linux)
        if [[ -z ${JAVA_HOME:-} ]]; then
            javac=$(command -v javac) || fail "javac required (JDK 21)"
            export JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$javac")")")
        fi
        export ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
        host=linux-x86_64 ;;
    *) fail "unsupported native build host" ;;
esac
export PATH="$JAVA_HOME/bin:$PATH"
expected=$(cat "$root/scripts/android-ndk-version")
[[ "$expected" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "invalid expected NDK revision"
ndk_dir="$ANDROID_HOME/ndk/$expected"
# Reject inherited runner defaults; source.properties, not a directory name, proves revision.
for override in ANDROID_NDK_HOME ANDROID_NDK_ROOT NDK_HOME NDK_ROOT; do
    value=${!override:-}
    if [[ -n "$value" ]]; then
        [[ -d "$value" && -d "$ndk_dir" ]] || fail "NDK override conflict: $override; expected $ndk_dir"
        [[ "$(cd "$value" && pwd -P)" == "$(cd "$ndk_dir" && pwd -P)" ]] || fail "NDK override conflict: $override; expected $ndk_dir"
    fi
done
[[ -f "$ndk_dir/source.properties" ]] || fail "missing NDK source.properties: $ndk_dir (install ndk;$expected)"
revision=$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ndk_dir/source.properties" | tr -d '\r')
[[ "$revision" == "$expected" ]] || fail "NDK revision mismatch: expected $expected, got $revision"
export ANDROID_NDK_HOME=$(cd "$ndk_dir" && pwd -P)
printf 'NDK %s\nRevision %s\n' "$ANDROID_NDK_HOME" "$revision" | tee "$run_evidence/selected-ndk.txt"
toolbin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin"
for tool in clang ld.lld aarch64-linux-android31-clang llvm-ar llvm-strip; do
    [[ -f "$toolbin/$tool" && -x "$toolbin/$tool" ]] || fail "NDK executable missing: $toolbin/$tool"
done
[[ -x "$JAVA_HOME/bin/javac" ]] || fail "JDK executable missing: $JAVA_HOME/bin/javac"
command -v python3 >/dev/null || fail "python3 required for native artifact validation"

mobile=$(go -C "$root/tailnet" list -m -f '{{.Version}}' golang.org/x/mobile)
gobin=${GOBIN:-$(go env GOPATH | cut -d: -f1)/bin}
# Compile helpers with the process-selected Go (setup-go in CI), rather than an
# implicitly downloaded toolchain. The target module may still select its toolchain.
GOBIN="$gobin" GOTOOLCHAIN=local go install "golang.org/x/mobile/cmd/gomobile@$mobile" "golang.org/x/mobile/cmd/gobind@$mobile"
python3 - "$root" "$ANDROID_NDK_HOME" "$toolbin" "$gobin" "$run_evidence/toolchain.json" <<'PY'
import hashlib, json, os, pathlib, shutil, subprocess, sys
root, ndk, tools, gobin, output = sys.argv[1:]
def run(args, **kwargs):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, **kwargs).strip()
def file(path):
    p = pathlib.Path(path).resolve(strict=True)
    return dict(path=str(p), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
go = shutil.which('go')
bootstrap = run([go, 'env', 'GOVERSION'], env=dict(os.environ, GOTOOLCHAIN='local'))
record = dict(ndk=ndk, revision=pathlib.Path(ndk, 'source.properties').read_text(),
              bootstrap_go=bootstrap, target_go=run([go, '-C', root+'/tailnet', 'env', 'GOVERSION']),
              go=file(go), head=run(['git', '-C', root, 'rev-parse', 'HEAD']),
              tree=run(['git', '-C', root, 'rev-parse', 'HEAD^{tree}']), helpers={}, tools={})
for name in ('gomobile', 'gobind'):
    path = str(pathlib.Path(gobin, name))
    info = run([go, 'version', '-m', path])
    if info.splitlines()[0].rsplit(': ', 1)[-1] != bootstrap:
        raise SystemExit(f'{name}: helper compiler does not match process-selected {bootstrap}')
    record['helpers'][name] = dict(**file(path), buildinfo=info)
for name in ('clang', 'ld.lld'):
    path = str(pathlib.Path(tools, name))
    record['tools'][name] = dict(**file(path), version=run([path, '--version']))
record['sources'] = {p: file(pathlib.Path(root, p)) for p in
                     ('scripts/build-tailnet.sh', 'scripts/android-ndk-version',
                      'scripts/validate-native-artifact.py', 'tailnet/go.mod', 'tailnet/go.sum')}
pathlib.Path(output).write_text(json.dumps(record, indent=2)+'\n')
print(json.dumps(record, indent=2))
PY

mkdir -p "$root/app/libs"
cd "$root/tailnet"
PATH="$gobin:$PATH" "$gobin/gomobile" bind \
    -target=android/arm64 \
    -androidapi 31 \
    -javapkg org.evsyukov.hermes.tailnet.go \
    -ldflags "-s -w" \
    -trimpath \
    -o "$root/app/libs/tailnet.aar" \
    .
python3 "$root/scripts/validate-native-artifact.py" "$root/app/libs/tailnet.aar" \
    --report "$run_evidence/tailnet-aar.json"
ls -lh "$root/app/libs/tailnet.aar"

# The licenses of the modules just linked, for the Licenses screen. The tool runs on this machine
# and lists the packages for the phone, the node and the gomobile glue.
tools=$(mktemp -d)
go build -o "$tools/licenses" ./licenses
mkdir -p "$root/app/libs/licenses"
GOOS=android GOARCH=arm64 CGO_ENABLED=1 "$tools/licenses" "$root/app/libs/licenses/tailnet.json" . golang.org/x/mobile/bind/seq
ls -lh "$root/app/libs/licenses/tailnet.json"
