# Firebase Test Lab Reference

## Required Inputs

- `PROJECT`: Google Cloud / Firebase project id.
- `RESULTS_BUCKET`: Existing `gs://...` bucket where Firebase raw results can be written.
- Model input: prefer an existing `gs://.../translategemma-4b-it-int4-multimodal.litertlm` object. The runner signs it and passes the signed HTTPS URL to the device.
- `MODEL_GCS_URI`: required only when the model input is a local file; the runner uploads the file there, signs it, and downloads it on device.
- `MODEL_DOWNLOAD_URL`: optional public HTTPS override, for example a Hugging Face artifact URL after the multimodal model has been uploaded.
- Service-account key path supplied at runtime only; never commit it.

## Required APIs

Enable these APIs in the project:

- `testing.googleapis.com`
- `toolresults.googleapis.com`
- `storage.googleapis.com`

## Service Account Permissions

Grant the service account these roles, scoped as narrowly as practical:

- Project: `roles/cloudtestservice.testAdmin`
  - Needed to create and run Firebase Test Lab matrices.
- Project: `roles/firebase.analyticsViewer`
  - Firebase Test Lab IAM guidance lists this alongside Test Lab Admin for running tests.
- Results/model bucket: `roles/storage.objectAdmin`
  - Needed to upload APK inputs, write/read pulled result artifacts, and optionally stage the large `.litertlm`.
- Service account key or IAM signing ability:
  - Needed when the model input is a private `gs://` object and the runner must create a temporary signed HTTPS URL for on-device download.

If organization policy forbids `storage.objectAdmin`, create a custom bucket-scoped role with object create/get/list/delete permissions for the dedicated Test Lab bucket.

Official references:

- Firebase Test Lab IAM permissions: https://firebase.google.com/docs/test-lab/android/iam-permissions-reference
- Firebase Test Lab role permissions: https://docs.cloud.google.com/iam/docs/roles-permissions/cloudtestservice
- Cloud Tool Results permissions: https://docs.cloud.google.com/iam/docs/roles-permissions/cloudtoolresults

## Device Matrix

The target matrix is:

- Pixel 10: Firebase model id `frankel`, Android `36`
- Galaxy S22: Firebase model id `r0q`, Android `36`

Always run catalog preflight before launching the matrix; availability can change by project, region, quota, or Firebase catalog updates.

Latest baseline:

- Pixel 10 `frankel` API 36: CPU text+image translation passed with the multimodal model in about 70s.
- Galaxy S22 `r0q` API 36: CPU text+image translation passed with the multimodal model in about 119s.

## APKs and Staged Suite

Build artifacts:

- App: `app/build/outputs/apk/full/debug/app-full-debug.apk`
- Test: `app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk`

The runner submits every stage/device in parallel:

- `launch`: installs and opens the app.
- `settings`: opens the overflow menu and verifies core settings rows.
- `ltr`: verifies left-to-right text entry without loading TranslateGemma.
- `rtl`: starts the app in Hebrew and verifies right-to-left text entry.
- `robo`: lets Firebase Robo crawl the app.
- `model`: downloads the `.litertlm` directly into the app model directory, then verifies TranslateGemma text and image translation on CPU and GPU.

Use `STAGES` to limit a run while iterating:

```bash
STAGES=launch,settings,ltr,rtl ./scripts/firebase_test_lab_smoke.sh app.apk test.apk hf
STAGES=model ./scripts/firebase_test_lab_smoke.sh app.apk test.apk gs://your-bucket/translategemma/model.litertlm
```

For the model stage, avoid Firebase `--other-files` for the 2.8 GB model. It can make the matrix take more than an hour because Firebase stages the large file and can pull it back. The optimized path passes `MODEL_DOWNLOAD_URL` to instrumentation and downloads the model into `TranslateGemmaEngine.getModelFile(context)`.

If `hf` is used, verify the exact multimodal `.litertlm` path exists in the Hugging Face repo first. A stale HF URL fails immediately with HTTP 404 on both devices.

With the signed GCS or public Hugging Face download path, the model matrix is now roughly 8-10 minutes per device instead of the older ~1.5 hour Firebase `--other-files` path.

## Vast.ai Machine Guidance

For TranslateGemma multimodal export/conversion, use a Vast.ai PyTorch image with:

- Ubuntu-based PyTorch/CUDA image from Vast or NVIDIA, not a bare CUDA image.
- Python 3.12, `uv`/pip, Git, Git LFS, Hugging Face CLI, and enough disk for model caches and outputs.
- CUDA 12.8+ compatible PyTorch wheels if using a Blackwell GPU; otherwise match PyTorch wheels to the GPU architecture.
- At least 96 GB system RAM. Prefer 128 GB RAM to leave headroom above the observed ~73.9 GiB peak export RSS.
- At least 80 GB free disk; 150+ GB is safer when keeping source model, conversion cache, build outputs, and staged artifacts.

GPU size is less important than system RAM for the export path. Prefer reliable RAM/disk over renting the largest GPU.
