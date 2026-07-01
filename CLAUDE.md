# CLAUDE.md

## Project Notes

- PrivateAITranslate is an Android app focused on on-device TranslateGemma translation.
- Do not commit service-account JSON, Firebase project IDs, bucket names, auth tokens, or device-session URLs.
- Real-device E2E docs live in `.codex/skills/translategemma-e2e-testing/`.
- The optimized model test path uses a signed GCS URL or a public Hugging Face URL, not Firebase `--other-files`, for the large multimodal `.litertlm` file.
- Verify the exact Hugging Face multimodal artifact URL before a run; missing paths fail with HTTP 404 on device.

## Current E2E Baseline

- Pixel 10 `frankel` API 36 and Galaxy S22 `r0q` API 36 pass CPU text+image translation with the multimodal model.
- CPU model-stage timings are about 70s on Pixel 10 and 119s on Galaxy S22.
- The optimized model matrix is about 8-10 minutes per device instead of the older ~1.5 hour Firebase `--other-files` path.
- GPU currently fails on both devices with a LiteRT-LM engine initialization error; CPU fallback is not a GPU pass.

## GPU and NPU Work

- Keep GPU failures visible in instrumentation logs, including requested backend and initialization exception.
- The current Java LiteRT-LM dependency exposes CPU/GPU only.
- NPU support likely needs a separate LiteRT-LM/`CompiledModel` integration or vendor accelerator path.
