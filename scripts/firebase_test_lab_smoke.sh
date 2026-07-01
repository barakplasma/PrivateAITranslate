#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/firebase_test_lab_smoke.sh APP_DEBUG_APK TEST_APK MODEL_FILE_OR_GS_URI

Runs the TranslateGemma real-device instrumentation matrix on Firebase Test Lab.

Environment:
  PROJECT          GCP project id. Defaults to active gcloud project.
  GCLOUD           gcloud binary. Default: /workspace/google-cloud-sdk/bin/gcloud
  TIMEOUT          Firebase test timeout. Default: 45m
  RESULTS_BUCKET   Optional gs:// bucket for raw Firebase results.
  MODEL_GCS_URI    Required when MODEL_FILE_OR_GS_URI is local; target gs:// URI
                   used to stage the 2.8 GB model once before the matrix.
EOF
}

if [[ $# -ne 3 ]]; then
  usage
  exit 2
fi

GCLOUD="${GCLOUD:-/workspace/google-cloud-sdk/bin/gcloud}"
PROJECT="${PROJECT:-$("$GCLOUD" config get-value project 2>/dev/null || true)}"
TIMEOUT="${TIMEOUT:-45m}"
APP_APK="$1"
TEST_APK="$2"
MODEL_INPUT="$3"
MODEL_SOURCE="$MODEL_INPUT"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
MODEL_DEVICE_PATH="/sdcard/Android/data/com.barakplasma.privateaitranslate.debug/files/translategemma/translategemma-4b-it-int4-multimodal.litertlm"
PULL_DIR="/sdcard/Download"
TEST_TARGET="class com.barakplasma.privateaitranslate.TranslateGemmaRealDeviceTest"

test -f "$APP_APK"
test -f "$TEST_APK"
if [[ "$MODEL_INPUT" != gs://* ]]; then
  test -f "$MODEL_INPUT"
fi
if [[ -z "$PROJECT" || "$PROJECT" == "(unset)" ]]; then
  echo "PROJECT is required, either as an environment variable or an active gcloud project." >&2
  exit 1
fi

require_gcloud_account() {
  if ! "$GCLOUD" auth list --filter=status:ACTIVE --format='value(account)' | grep -q .; then
    echo "No active gcloud account. Run gcloud auth activate-service-account or gcloud auth login first." >&2
    exit 1
  fi
}

require_service() {
  local service="$1"
  if ! "$GCLOUD" services list --enabled --project "$PROJECT" --format='value(config.name)' | grep -Fxq "$service"; then
    echo "Required service is not enabled for $PROJECT: $service" >&2
    exit 1
  fi
}

stage_model_if_needed() {
  if [[ "$MODEL_INPUT" == gs://* ]]; then
    MODEL_SOURCE="$MODEL_INPUT"
    return
  fi

  if [[ -z "${MODEL_GCS_URI:-}" ]]; then
    echo "MODEL_GCS_URI=gs://bucket/path/model.litertlm is required for local model input." >&2
    exit 1
  fi

  echo "Staging model to $MODEL_GCS_URI" >&2
  "$GCLOUD" storage cp "$MODEL_INPUT" "$MODEL_GCS_URI"
  MODEL_SOURCE="$MODEL_GCS_URI"
}

require_device_version() {
  local model="$1"
  local version="$2"
  local model_json
  model_json="$("$GCLOUD" firebase test android models describe "$model" \
    --project "$PROJECT" \
    --format=json)"
  python3 -c '
import json
import sys

version = sys.argv[1]
model = sys.argv[2]
data = json.load(sys.stdin)
versions = {str(v) for v in data.get("supportedVersionIds", [])}
if version not in versions:
    raise SystemExit(f"Firebase model {model} does not support Android {version}; supported={sorted(versions)}")
' "$version" "$model" <<<"$model_json"
}

run_instrumentation() {
  local label="$1"
  local model="$2"
  local version="$3"
  local results_dir="translategemma-${label}-${model}-${version}-${STAMP}"
  local args=(
    firebase test android run
    --project "$PROJECT"
    --type instrumentation
    --app "$APP_APK"
    --test "$TEST_APK"
    --device "model=${model},version=${version},locale=en,orientation=portrait"
    --test-targets "$TEST_TARGET"
    --timeout "$TIMEOUT"
    --results-dir "$results_dir"
    --directories-to-pull "$PULL_DIR"
    --other-files "${MODEL_DEVICE_PATH}=${MODEL_SOURCE}"
    --client-details "matrixLabel=TranslateGemma ${label} ${model} API ${version}"
    --no-record-video
  )

  if [[ -n "${RESULTS_BUCKET:-}" ]]; then
    args+=(--results-bucket "$RESULTS_BUCKET")
  fi

  "$GCLOUD" "${args[@]}"
}

require_gcloud_account
require_service testing.googleapis.com
require_service toolresults.googleapis.com
require_service storage.googleapis.com
require_device_version frankel 36
require_device_version r0q 36
stage_model_if_needed

run_instrumentation pixel10 frankel 36
run_instrumentation galaxy-s22 r0q 36
