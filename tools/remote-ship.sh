#!/usr/bin/env bash
# remote-ship.sh — run tools/ship.sh on the build host and bring the verdict back here.
#
# For a dev machine whose memory belongs to something else (a local model server, say): a Gradle
# build is two JVMs and ~4-5 GB at peak. So the build runs on the host named in
# ~/.config/pygmalion/build-host (user@host) — a machine with an Android SDK env script at
# $PYG_ANDROID_ENV (default ~/android-buildenv/env.sh) — and the result lands here exactly as if
# ship.sh had run locally:
#
#   1. rsync the working tree, uncommitted changes included, to <host>:~/workspace/pygmalion
#   2. ssh <host> tools/ship.sh --local <args>          (streams the same output)
#   3. rsync back build/ship/ (status, ship.log) and the APK outputs
#   4. exit with ship.sh's own code: 0 GREEN / 1 RED / 3 AMBER / 2 setup error
#
# ship.sh hands off here by itself when the build-host file exists. `--local` skips the
# hand-off. An unreachable build host is AMBER: nothing was learned about the code.
# --smoke needs the phone reachable from the BUILD HOST (adb over LAN), not from here.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST_FILE="${PYG_BUILD_HOST_FILE:-$HOME/.config/pygmalion/build-host}"
REMOTE_ROOT="workspace/pygmalion"                     # relative to $HOME on the build host
OUT="$REPO_ROOT/build/ship"
mkdir -p "$OUT"
LOG="$OUT/ship.log"

HOST="$(cat "$HOST_FILE" 2>/dev/null | tr -d '[:space:]')"
if [ -z "$HOST" ]; then echo "no build host named in $HOST_FILE" >&2; exit 2; fi

DETACH=0; args=()
for a in "$@"; do case "$a" in
  --detach) DETACH=1 ;;
  --local)  echo "--local belongs to ship.sh, not the remote wrapper" >&2; exit 2 ;;
  *) args+=("$a") ;;
esac; done

# --detach: background THIS wrapper so status and log land locally, same contract as ship.sh.
# The wrapper's own chatter goes to remote.log; ship.log is the build host's log, fetched back.
if [ "$DETACH" = 1 ]; then
  echo "RUNNING" > "$OUT/status"
  nohup "${BASH_SOURCE[0]}" "${args[@]}" >"$OUT/remote.log" 2>&1 &
  echo "detached pid $! (building on $HOST) — poll $OUT/status, read $LOG"
  exit 0
fi

amber() {
  echo "AMBER" > "$OUT/status"
  {
    printf '\n── PYGMALION SHIP VERDICT ─────────────────────────────\n'
    printf 'VERDICT:  AMBER\n'
    printf 'STAGES:   (none ran)\n'
    printf 'REASON:   %s\n' "$1"
    printf 'MEANING:  a stage could not run. Nothing was learned about the code.\n'
    printf '          Do NOT revert on AMBER and do NOT ship on AMBER.\n'
    printf '───────────────────────────────────────────────────\n'
  } | tee "$LOG"
  exit 3
}

SSH=(ssh -o BatchMode=yes -o ConnectTimeout=8 "$HOST")
RSYNC_EX=(--exclude=build/ --exclude=.gradle/ --exclude=.kotlin/ --exclude=dist/
          --exclude=backups/ --exclude='*.apk' --exclude=local.properties)

echo "── sync → $HOST:~/$REMOTE_ROOT"
"${SSH[@]}" "mkdir -p ~/$REMOTE_ROOT" >/dev/null 2>&1 || amber "build host $HOST unreachable over ssh"
rsync -a --delete "${RSYNC_EX[@]}" "$REPO_ROOT/" "$HOST:$REMOTE_ROOT/" || amber "rsync to $HOST failed"

# The signing key travels with the build. Without a release keystore in local.properties, a build
# is signed with the BUILDING machine's ~/.android/debug.keystore — and a build host mints its own
# the first time gradle runs there. An APK signed with it cannot update the app already on the
# phone (INSTALL_FAILED_UPDATE_INCOMPATIBLE), and the only way past that is an uninstall that
# wipes the app's data. So the host signs with THIS machine's key, every run.
KEYSTORE="$HOME/.android/debug.keystore"
if [[ -f "$KEYSTORE" ]]; then
  "${SSH[@]}" 'mkdir -p ~/.android && { [ ! -f ~/.android/debug.keystore ] || [ -f ~/.android/debug.keystore.host-own ] || cp -p ~/.android/debug.keystore ~/.android/debug.keystore.host-own; }'
  rsync -a "$KEYSTORE" "$HOST:.android/debug.keystore" || amber "could not hand the signing key to $HOST"
fi

"${SSH[@]}" "cd ~/$REMOTE_ROOT && tools/ship.sh --local ${args[*]}"
RC=$?

echo "── fetch results ← $HOST"
rsync -a "$HOST:$REMOTE_ROOT/build/ship/" "$OUT/" || echo "  could not fetch build/ship (verdict above still stands)"
mkdir -p "$REPO_ROOT/app/build/outputs/apk"
rsync -a --include='*/' --include='*.apk' --exclude='*' \
  "$HOST:$REMOTE_ROOT/app/build/outputs/apk/" "$REPO_ROOT/app/build/outputs/apk/" 2>/dev/null
exit $RC
