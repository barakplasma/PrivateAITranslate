# Tasks

## TranslateGemma Device Acceleration

- [ ] Validate the multimodal TranslateGemma bundle on Android CPU and GPU backends with Firebase Test Lab smoke tests on Pixel 10 and Galaxy S22.
- [ ] Add a Samsung-specific LiteRT Next investigation for Exynos AI LiteCore/NPU support using the Google LiteRT Samsung guidance: https://developers.google.com/edge/litert/next/samsung.md.txt
- [ ] Confirm whether target Samsung devices expose a supported SoC for LiteRT Next Samsung acceleration. The current LiteRT Samsung doc lists Exynos 2500 and Exynos 2600, so Galaxy S22 should remain a standard CPU/GPU target unless a separate supported Samsung device is added.
- [ ] If a supported Samsung device is available, prototype a LiteRT Next `CompiledModel`/LiteRT-LM NPU path and keep the existing CPU/GPU fallback behavior intact.
