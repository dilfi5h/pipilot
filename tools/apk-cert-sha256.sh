#!/usr/bin/env bash
# Print the APK signer cert SHA-256 (first signer), works on Linux CI and Windows.
# Usage: apk-cert-sha256.sh <apk>
set -euo pipefail
APK="$1"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
if command -v apksigner >/dev/null 2>&1; then
    APKSIGNER=apksigner
elif [ -n "${SDK:-}" ] && ls -d "$SDK"/build-tools/* >/dev/null 2>&1; then
    APKSIGNER=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/apksigner
else
    echo "apksigner not found (set ANDROID_HOME)" >&2; exit 2
fi
[ -f "$APKSIGNER" ] || APKSIGNER="${APKSIGNER}.bat"
[ -f "$APKSIGNER" ] || { echo "apksigner binary not found at either path" >&2; exit 2; }
"$APKSIGNER" verify --print-certs "$APK" \
    | grep -m1 "SHA-256 digest" \
    | sed 's/.*: //; s/ //g' \
    | tr 'A-F' 'a-f'
