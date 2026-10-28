#!/usr/bin/env bash
# Builds app/libs/tailnet.aar, the userspace Tailscale node of the app, with gomobile.
# Needs Go, the Android SDK and its NDK; arm64 only, the phones the app targets.
set -euo pipefail

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
export GOTOOLCHAIN=auto
# gomobile compiles its Java glue with javac; the macOS /usr/bin/java stub has none behind it.
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21)}
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ndk_dir=$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ndk_dir}

mobile=$(go -C "$root/tailnet" list -m -f '{{.Version}}' golang.org/x/mobile)
go install "golang.org/x/mobile/cmd/gomobile@$mobile" "golang.org/x/mobile/cmd/gobind@$mobile"
gobin=$(go env GOPATH)/bin

mkdir -p "$root/app/libs"
cd "$root/tailnet"
PATH="$gobin:$PATH" gomobile bind \
    -target=android/arm64 \
    -androidapi 31 \
    -javapkg org.evsyukov.hermes.tailnet.go \
    -ldflags "-s -w" \
    -trimpath \
    -o "$root/app/libs/tailnet.aar" \
    .
ls -lh "$root/app/libs/tailnet.aar"
