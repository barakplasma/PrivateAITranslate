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
5. Run the staged parallel suite:
   ```bash
   PROJECT="$PROJECT_ID" \
   RESULTS_BUCKET="gs://your-results-bucket" \
     .codex/skills/translategemma-e2e-testing/scripts/run-matrix.sh \
       gs://your-results-bucket/translategemma/model.litertlm
   ```
   Use `STAGES=launch,settings,ltr,rtl` for cheap UI-only checks, or
   `STAGES=model` when iterating on the heavy TranslateGemma load path.

## Rules

- Do not commit service-account JSON, project IDs, bucket names, tokens, or device-session URLs.
- Prefer the debug APK plus androidTest APK for automated assertions.
- Run the cheap app stages, Robo crawl, and heavy TranslateGemma stage as independent Firebase matrices so Pixel/Galaxy and stage failures are visible separately.
- For the heavy model stage, prefer a signed HTTPS download from a pre-uploaded GCS object or a public Hugging Face artifact. Avoid Firebase `--other-files` for the 2.8 GB model because Firebase also pulls/stages large files slowly.
- Treat GPU fallback as a test failure; the instrumentation test must prove the requested backend actually initialized.
- For conversion/export jobs, require at least 96 GB system RAM. Prefer 128 GB RAM when renting a Vast.ai node.
