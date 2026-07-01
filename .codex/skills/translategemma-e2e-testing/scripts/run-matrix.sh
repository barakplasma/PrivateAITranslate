#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 MODEL_FILE_OR_GS_URI" >&2
  exit 2
fi

MODEL_INPUT="$1"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GCLOUD="${GCLOUD:-gcloud}"
export GCLOUD

cd "$ROOT"

./gradlew :app:assembleFullDebug :app:assembleFullDebugAndroidTest --no-daemon

"$ROOT/scripts/firebase_test_lab_smoke.sh" \
  "$ROOT/app/build/outputs/apk/full/debug/app-full-debug.apk" \
  "$ROOT/app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk" \
  "$MODEL_INPUT"
