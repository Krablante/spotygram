#!/usr/bin/env bash
set -euo pipefail
ROOT=$(dirname "$(dirname "$(realpath "$0")")")
export SPOTYGRAM_STATE=${SPOTYGRAM_STATE:-${XDG_STATE_HOME:-$HOME/.local/state}/spotygram}
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
MODE=${1:-debug}
case "$MODE" in debug) TASK=assembleDebug ;; release) TASK=assembleRelease ;; *) printf 'Usage: %s [debug|release]\n' "$0"; exit 2 ;; esac
mkdir -p "$SPOTYGRAM_STATE"
if [ ! -f "$SPOTYGRAM_STATE/jniLibs/arm64-v8a/libtdjsonjava.so" ] || [ ! -f "$SPOTYGRAM_STATE/jniLibs/x86_64/libtdjsonjava.so" ]; then
  bash "$ROOT/tools/build-native.sh"
fi
if [ "$MODE" = release ] && [ ! -f "$SPOTYGRAM_STATE/signing.properties" ]; then
  printf 'Release signing is missing. See docs/build.md.\n' >&2
  exit 1
fi
exec "$ROOT/gradlew" -p "$ROOT" --project-cache-dir "$SPOTYGRAM_STATE/gradle" \
  -Pkotlin.project.persistent.dir="$SPOTYGRAM_STATE/kotlin" ":app:$TASK" --console=plain --no-daemon
