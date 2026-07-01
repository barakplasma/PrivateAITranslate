#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 SERVICE_ACCOUNT_JSON PROJECT_ID" >&2
  exit 2
fi

KEY_FILE="$1"
PROJECT="$2"
GCLOUD="${GCLOUD:-gcloud}"

test -f "$KEY_FILE"
"$GCLOUD" auth activate-service-account --key-file="$KEY_FILE" --project="$PROJECT"
"$GCLOUD" config set project "$PROJECT"
"$GCLOUD" auth list --filter=status:ACTIVE
