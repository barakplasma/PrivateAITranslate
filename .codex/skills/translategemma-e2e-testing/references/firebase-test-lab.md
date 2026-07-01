# Firebase Test Lab Reference

## Required Inputs

- `PROJECT`: Google Cloud / Firebase project id.
- `RESULTS_BUCKET`: Existing `gs://...` bucket where Firebase raw results can be written.
- `MODEL_GCS_URI`: `gs://.../translategemma-4b-it-int4-multimodal.litertlm` destination used when staging a local model file.
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
  - Needed to stage the large `.litertlm`, upload APK inputs, and write/read pulled result artifacts.

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

## APKs and Model Placement

Build artifacts:

- App: `app/build/outputs/apk/full/debug/app-full-debug.apk`
- Test: `app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk`

The runner pushes the model to:

```text
/sdcard/Android/data/com.barakplasma.privateaitranslate.debug/files/translategemma/translategemma-4b-it-int4-multimodal.litertlm
```

This matches `TranslateGemmaEngine.getModelFile(context)` for the debug package.

## Vast.ai Machine Guidance

For TranslateGemma multimodal export/conversion, use a Vast.ai PyTorch image with:

- Ubuntu-based PyTorch/CUDA image from Vast or NVIDIA, not a bare CUDA image.
- Python 3.12, `uv`/pip, Git, Git LFS, Hugging Face CLI, and enough disk for model caches and outputs.
- CUDA 12.8+ compatible PyTorch wheels if using a Blackwell GPU; otherwise match PyTorch wheels to the GPU architecture.
- At least 96 GB system RAM. Prefer 128 GB RAM to leave headroom above the observed ~73.9 GiB peak export RSS.
- At least 80 GB free disk; 150+ GB is safer when keeping source model, conversion cache, build outputs, and staged artifacts.

GPU size is less important than system RAM for the export path. Prefer reliable RAM/disk over renting the largest GPU.
