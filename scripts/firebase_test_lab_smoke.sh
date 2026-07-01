#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/firebase_test_lab_smoke.sh APP_DEBUG_APK TEST_APK MODEL_FILE_OR_GS_URI

Runs the PrivateAITranslate staged real-device suite on Firebase Test Lab.
All stages and devices are submitted in parallel:
  - launch smoke instrumentation
  - settings smoke instrumentation
  - left-to-right input instrumentation
  - right-to-left input instrumentation
  - Robo crawl
  - TranslateGemma text/image CPU/GPU instrumentation

Environment:
  PROJECT          GCP project id. Defaults to active gcloud project.
  GCLOUD           gcloud binary. Default: /workspace/google-cloud-sdk/bin/gcloud
  TIMEOUT          Firebase test timeout. Default: 45m
  RESULTS_BUCKET   Optional gs:// bucket for raw Firebase results.
  MODEL_GCS_URI    gs:// destination used when MODEL_FILE_OR_GS_URI is local.
                   The model stage signs the GCS object and downloads it on
                   device instead of staging the 2.8 GB model through Firebase.
  MODEL_DOWNLOAD_URL
                   URL the model stage downloads on device. Defaults to the
                   app's Hugging Face model artifact.
  GCS_SIGN_KEY_FILE
                   Optional service-account JSON key for signing gs:// inputs.
                   Defaults to /workspace/secrets/gcp-service-account.json when
                   present.
  STAGES           Optional comma-separated stage list. Default:
                   launch,settings,ltr,rtl,robo,model
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
MODEL_DOWNLOAD_URL="${MODEL_DOWNLOAD_URL:-https://huggingface.co/barakplasma/translategemma-4b-it-android-task-quantized/resolve/main/artifacts/int4-multimodal/translategemma-4b-it-int4-multimodal.litertlm}"
PULL_DIR="/sdcard/Download"
MODEL_TEST_TARGET="class com.barakplasma.privateaitranslate.TranslateGemmaRealDeviceTest"
SMOKE_TEST_CLASS="com.barakplasma.privateaitranslate.AppSmokeRealDeviceTest"
LAUNCH_TEST_TARGET="class com.barakplasma.privateaitranslate.AppLaunchRealDeviceTest#launchesMainActivity"
STAGES="${STAGES:-launch,settings,ltr,rtl,robo,model}"
GCS_SIGN_KEY_FILE="${GCS_SIGN_KEY_FILE:-}"
JOBS=()

test -f "$APP_APK"
test -f "$TEST_APK"
if [[ "$MODEL_INPUT" != gs://* && "$MODEL_INPUT" != http://* && "$MODEL_INPUT" != https://* && "$MODEL_INPUT" != "hf" ]]; then
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
  if [[ "$MODEL_INPUT" == "hf" ]]; then
    MODEL_SOURCE="$MODEL_DOWNLOAD_URL"
    return
  fi

  if [[ "$MODEL_INPUT" == http://* || "$MODEL_INPUT" == https://* ]]; then
    MODEL_DOWNLOAD_URL="$MODEL_INPUT"
    MODEL_SOURCE="$MODEL_INPUT"
    return
  fi

  if [[ "$MODEL_INPUT" == gs://* ]]; then
    MODEL_SOURCE="$MODEL_INPUT"
    MODEL_DOWNLOAD_URL="$(sign_gcs_url "$MODEL_SOURCE")"
    return
  fi

  if [[ -z "${MODEL_GCS_URI:-}" ]]; then
    echo "MODEL_GCS_URI=gs://bucket/path/model.litertlm is required for local model input." >&2
    echo "Alternatively pass hf, an https:// URL, or a gs:// object that can be signed." >&2
    exit 1
  fi

  echo "Uploading local model to $MODEL_GCS_URI" >&2
  "$GCLOUD" storage cp "$MODEL_INPUT" "$MODEL_GCS_URI"
  MODEL_SOURCE="$MODEL_GCS_URI"
  MODEL_DOWNLOAD_URL="$(sign_gcs_url "$MODEL_SOURCE")"
}

sign_gcs_url() {
  local gcs_uri="$1"
  local args=(storage sign-url "$gcs_uri" --duration=12h --format='value(signed_url)')

  if [[ -z "$GCS_SIGN_KEY_FILE" && -f /workspace/secrets/gcp-service-account.json ]]; then
    GCS_SIGN_KEY_FILE=/workspace/secrets/gcp-service-account.json
  fi
  if [[ -n "$GCS_SIGN_KEY_FILE" ]]; then
    args+=(--private-key-file="$GCS_SIGN_KEY_FILE")
  fi

  "$GCLOUD" "${args[@]}"
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

run_gcloud_job() {
  local name="$1"
  shift

  (
    set -o pipefail
    "$GCLOUD" "$@" 2>&1 | awk -v prefix="[${name}] " '{ print prefix $0; fflush() }'
  ) &
  JOBS+=("$!:$name")
}

run_instrumentation() {
  local stage="$1"
  local label="$2"
  local model="$3"
  local version="$4"
  local test_target="$5"
  local include_model="${6:-false}"
  local locale="${7:-en}"
  local results_dir="translategemma-${stage}-${label}-${model}-${version}-${STAMP}"
  local job_name="${stage}/${label}"
  local args=(
    firebase test android run
    --project "$PROJECT"
    --type instrumentation
    --app "$APP_APK"
    --test "$TEST_APK"
    --device "model=${model},version=${version},locale=${locale},orientation=portrait"
    --test-targets "$test_target"
    --timeout "$TIMEOUT"
    --results-dir "$results_dir"
    --client-details "matrixLabel=PrivateAITranslate ${stage} ${label} ${model} API ${version}"
    --no-record-video
  )

  if [[ "$include_model" == "true" ]]; then
    args+=(
      --directories-to-pull "$PULL_DIR"
      --environment-variables "MODEL_DOWNLOAD_URL=${MODEL_DOWNLOAD_URL}"
    )
  fi

  if [[ -n "${RESULTS_BUCKET:-}" ]]; then
    args+=(--results-bucket "$RESULTS_BUCKET")
  fi

  run_gcloud_job "$job_name" "${args[@]}"
}

run_robo() {
  local label="$1"
  local model="$2"
  local version="$3"
  local results_dir="translategemma-robo-${label}-${model}-${version}-${STAMP}"
  local job_name="robo/${label}"
  local args=(
    firebase test android run
    --project "$PROJECT"
    --type robo
    --app "$APP_APK"
    --device "model=${model},version=${version},locale=en,orientation=portrait"
    --timeout "$TIMEOUT"
    --results-dir "$results_dir"
    --client-details "matrixLabel=PrivateAITranslate robo ${label} ${model} API ${version}"
    --no-record-video
  )

  if [[ -n "${RESULTS_BUCKET:-}" ]]; then
    args+=(--results-bucket "$RESULTS_BUCKET")
  fi

  run_gcloud_job "$job_name" "${args[@]}"
}

run_suite_for_device() {
  local label="$1"
  local model="$2"
  local version="$3"

  if stage_enabled launch; then
    run_instrumentation launch "$label" "$model" "$version" "$LAUNCH_TEST_TARGET"
  fi
  if stage_enabled settings; then
    run_instrumentation settings "$label" "$model" "$version" "class ${SMOKE_TEST_CLASS}#opensSettingsAndShowsCoreOptions"
  fi
  if stage_enabled ltr; then
    run_instrumentation ltr "$label" "$model" "$version" "class ${SMOKE_TEST_CLASS}#acceptsLeftToRightTextInput"
  fi
  if stage_enabled rtl; then
    run_instrumentation rtl "$label" "$model" "$version" "class ${SMOKE_TEST_CLASS}#acceptsRightToLeftTextInput"
  fi
  if stage_enabled robo; then
    run_robo "$label" "$model" "$version"
  fi
  if stage_enabled model; then
    run_instrumentation model "$label" "$model" "$version" "$MODEL_TEST_TARGET" true
  fi
}

stage_enabled() {
  local stage="$1"
  [[ ",${STAGES}," == *",${stage},"* ]]
}

wait_for_jobs() {
  local failed=0
  local entry pid name
  for entry in "${JOBS[@]}"; do
    pid="${entry%%:*}"
    name="${entry#*:}"
    if wait "$pid"; then
      echo "[$name] completed successfully" >&2
    else
      echo "[$name] failed" >&2
      failed=1
    fi
  done
  return "$failed"
}

require_gcloud_account
require_service testing.googleapis.com
require_service toolresults.googleapis.com
require_service storage.googleapis.com
require_device_version frankel 36
require_device_version r0q 36
stage_model_if_needed

run_suite_for_device pixel10 frankel 36
run_suite_for_device galaxy-s22 r0q 36
wait_for_jobs
