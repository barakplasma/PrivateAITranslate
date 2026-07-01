---
name: translategemma-e2e-testing
description: Run or maintain PrivateAITranslate real-device E2E verification for the multimodal TranslateGemma LiteRT-LM model. Use when testing Android APKs on Firebase Test Lab Pixel/Galaxy devices, configuring service-account permissions, staging the .litertlm model, checking CPU/GPU image translation, or choosing a Vast.ai machine for TranslateGemma conversion/testing.
---

# TranslateGemma E2E Testing

Use this skill for real-device verification of PrivateAITranslate with the multimodal TranslateGemma `.litertlm` bundle.

## Workflow

1. Read `references/firebase-test-lab.md` for IAM, API, bucket, and device requirements.
2. Build the debug app and instrumentation APK:
   ```bash
   ./gradlew :app:assembleFullDebug :app:assembleFullDebugAndroidTest --no-daemon
   ```
3. Authenticate a service account without committing secrets:
   ```bash
   .codex/skills/translategemma-e2e-testing/scripts/activate-service-account.sh \
     /path/to/service-account.json \
     "$PROJECT_ID"
   ```
4. Run preflight:
   ```bash
   PROJECT="$PROJECT_ID" RESULTS_BUCKET="gs://your-results-bucket" \
     .codex/skills/translategemma-e2e-testing/scripts/preflight.sh
   ```
5. Run the matrix:
   ```bash
   PROJECT="$PROJECT_ID" \
   RESULTS_BUCKET="gs://your-results-bucket" \
   MODEL_GCS_URI="gs://your-results-bucket/translategemma/model.litertlm" \
     .codex/skills/translategemma-e2e-testing/scripts/run-matrix.sh \
       /path/to/translategemma-4b-it-int4-multimodal.litertlm
   ```

## Rules

- Do not commit service-account JSON, project IDs, bucket names, tokens, or device-session URLs.
- Prefer the debug APK plus androidTest APK for automated model assertions.
- Treat GPU fallback as a test failure; the instrumentation test must prove the requested backend actually initialized.
- For conversion/export jobs, require at least 96 GB system RAM. Prefer 128 GB RAM when renting a Vast.ai node.
