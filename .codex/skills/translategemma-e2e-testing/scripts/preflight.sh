#!/usr/bin/env bash
set -euo pipefail

GCLOUD="${GCLOUD:-gcloud}"
PROJECT="${PROJECT:-$("$GCLOUD" config get-value project 2>/dev/null || true)}"
RESULTS_BUCKET="${RESULTS_BUCKET:-}"

if [[ -z "$PROJECT" || "$PROJECT" == "(unset)" ]]; then
  echo "PROJECT is required, either as an environment variable or active gcloud project." >&2
  exit 1
fi

if ! "$GCLOUD" auth list --filter=status:ACTIVE --format='value(account)' | grep -q .; then
  echo "No active gcloud account." >&2
  exit 1
fi

require_service() {
  local service="$1"
  if ! "$GCLOUD" services list --enabled --project "$PROJECT" --format='value(config.name)' | grep -Fxq "$service"; then
    echo "Missing enabled API: $service" >&2
    exit 1
  fi
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

require_service testing.googleapis.com
require_service toolresults.googleapis.com
require_service storage.googleapis.com
require_device_version frankel 36
require_device_version r0q 36

if [[ -n "$RESULTS_BUCKET" ]]; then
  "$GCLOUD" storage ls "$RESULTS_BUCKET" >/dev/null
fi

echo "Firebase Test Lab preflight passed for project $PROJECT"
