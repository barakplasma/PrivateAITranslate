#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 PRIVATE_AI_TRANSLATE_APK [MODEL_TEST_APK]" >&2
  exit 2
fi

PROJECT="${PROJECT:-personal-398106}"
GCLOUD="${GCLOUD:-/workspace/google-cloud-sdk/bin/gcloud}"
PRIVATE_APK="$1"
MODEL_APK="${2:-}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"

run_robo() {
  local label="$1"
  local apk="$2"
  local model="$3"
  local version="$4"
  local results_dir="${label}-${model}-${version}-${STAMP}"

  "$GCLOUD" firebase test android run \
    --project "$PROJECT" \
    --type robo \
    --app "$apk" \
    --device "model=${model},version=${version},locale=en,orientation=portrait" \
    --timeout "${TIMEOUT:-2m}" \
    --results-dir "$results_dir" \
    --no-record-video \
    --no-performance-metrics
}

run_matrix_for_apk() {
  local label="$1"
  local apk="$2"
  test -f "$apk"
  run_robo "$label" "$apk" frankel 36
  run_robo "$label" "$apk" r0q 36
}

run_matrix_for_apk privateaitranslate "$PRIVATE_APK"

if [[ -n "$MODEL_APK" ]]; then
  run_matrix_for_apk image-text-model "$MODEL_APK"
else
  echo "[!] MODEL_TEST_APK not supplied; skipped image/text model app matrix." >&2
fi
