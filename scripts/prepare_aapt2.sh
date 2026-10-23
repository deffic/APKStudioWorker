#!/usr/bin/env bash
# CI bootstrap only: package a pinned Android-native executable as a JNI asset.
# Never execute a downloaded binary from the writable app files directory.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
VERSION='v1.1.0'
EXPECTED_SHA256='7e5ae2e1f62fc24cab14072555ffd0a1a7e1ce27e82cc1008967b835b6d8df5b'
ASSET_URL="https://github.com/ReVanced/aapt2/releases/download/${VERSION}/aapt2-arm64-v8a"
LICENSE_URL="https://raw.githubusercontent.com/ReVanced/aapt2/${VERSION}/LICENSE"
DEST="$REPO_DIR/app/src/main/jniLibs/arm64-v8a/libaapt2.so"
LICENSE_DEST="$REPO_DIR/app/src/main/assets/third_party/revanced_aapt2/LICENSE"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

if [[ -n "${APK_STUDIO_AAPT2_SOURCE:-}" ]]; then
    # Test-only local source, subject to exactly the same SHA-256 + ELF checks.
    cp -- "$APK_STUDIO_AAPT2_SOURCE" "$WORK_DIR/aapt2"
else
    curl --fail --location --proto '=https' --proto-redir '=https' \
      --retry 3 --connect-timeout 15 --max-time 120 \
      --silent --show-error "$ASSET_URL" --output "$WORK_DIR/aapt2"
fi

printf '%s  %s\n' "$EXPECTED_SHA256" "$WORK_DIR/aapt2" | sha256sum --check --status || {
    echo 'AAPT2: SHA-256 mismatch; aborting packaging.' >&2
    exit 1
}

java "$SCRIPT_DIR/VerifyAapt2.java" "$WORK_DIR/aapt2"

# The full applicable license travels with the app. Download from the same
# immutable release tag. Fail closed if it is unavailable or unexpectedly small.
curl --fail --location --proto '=https' --proto-redir '=https' \
    --retry 3 --connect-timeout 15 --max-time 90 \
    --silent --show-error "$LICENSE_URL" --output "$WORK_DIR/LICENSE"
if [[ $(wc -c < "$WORK_DIR/LICENSE") -lt 10000 ]] || \
    ! grep -q 'GNU GENERAL PUBLIC LICENSE' "$WORK_DIR/LICENSE"; then
    echo 'AAPT2: GPL license retrieval or validation failed.' >&2
    exit 1
fi

mkdir -p -- "$(dirname -- "$DEST")" "$(dirname -- "$LICENSE_DEST")"
install -m 0755 "$WORK_DIR/aapt2" "$DEST"
install -m 0644 "$WORK_DIR/LICENSE" "$LICENSE_DEST"
echo 'Verified ARM64 AAPT2 and GPL-3.0 license packaged for Android.'
