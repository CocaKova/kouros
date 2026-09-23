#!/usr/bin/env bash
# sign-release.sh — sign the release APK with the release key, on THIS machine.
#
# The build host never sees the release key: remote-ship.sh builds the release APK there (signed
# with the throwaway debug key) and brings it back; this script re-signs it here with the key
# named in local.properties (kouros.keystore / .password / key.alias / key.password — the same
# properties a local Gradle build uses) and writes dist/Kouros-<version>.apk.
#
#   tools/sign-release.sh [path/to/app-foss-release.apk]
#
# apksigner replaces any existing signature. Needs apksigner on PATH, or ANDROID_HOME set, or the
# Android env script at $KOUROS_ANDROID_ENV (default ~/android-buildenv/env.sh).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$REPO_ROOT/local.properties"
IN="${1:-$REPO_ROOT/app/build/outputs/apk/foss/release/app-foss-release.apk}"

prop() { grep -E "^$1=" "$PROPS" 2>/dev/null | head -1 | cut -d= -f2-; }
KS="$(prop kouros.keystore)"
[ -n "$KS" ] || { echo "no kouros.keystore in local.properties — nothing to sign with" >&2; exit 2; }
[ -f "$KS" ] || { echo "keystore not found: $KS" >&2; exit 2; }
[ -f "$IN" ] || { echo "no release APK at $IN (run tools/ship.sh --release first)" >&2; exit 2; }

if ! command -v apksigner >/dev/null; then
  ENV_SH="${KOUROS_ANDROID_ENV:-$HOME/android-buildenv/env.sh}"
  # shellcheck disable=SC1090
  [ -f "$ENV_SH" ] && source "$ENV_SH"
  if ! command -v apksigner >/dev/null && [ -n "${ANDROID_HOME:-}" ]; then
    BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
    PATH="$BT:$PATH"
  fi
fi
command -v apksigner >/dev/null || { echo "apksigner not found" >&2; exit 2; }

VERSION="$(grep -E '^\s*versionName\s*=' "$REPO_ROOT/app/build.gradle.kts" | head -1 | sed -E 's/.*"(.*)".*/\1/')"
mkdir -p "$REPO_ROOT/dist"
OUT="$REPO_ROOT/dist/Kouros-$VERSION.apk"

KS_PASS="$(prop kouros.keystore.password)" KEY_PASS="$(prop kouros.key.password)" \
  apksigner sign --ks "$KS" --ks-key-alias "$(prop kouros.key.alias)" \
    --ks-pass env:KS_PASS --key-pass env:KEY_PASS --out "$OUT" "$IN"
rm -f "$OUT.idsig"

apksigner verify --print-certs "$OUT" | grep -E "Signer #1 certificate (DN|SHA-256)"
echo "signed: $OUT"
