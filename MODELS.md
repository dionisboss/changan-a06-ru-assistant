# Model weights not tracked by git

Two model files exceed GitHub's 100 MB per‑file limit and are therefore **excluded from git**
(see `.gitignore`). They are required to build. Place them at exactly these paths:

| File | Size | Path |
|---|---|---|
| GigaAM‑v3 CTC (int8) | 214 MB | `stand/asr-android/gigaam/model.int8.onnx` |
| TeraTTS distilled sampler (4‑step) | 245 MB | `tools/tera-tts-java/assets/models/sampler_distilled_cfg3_4step.onnx` |

All the other model files (TeraTTS `text_encoder` / `duration_predictor` / `vocoder`, the tokens, styles,
accent dict, and the JNI `.so` libs) are under the limit and **are** committed.

## Where to get them

- **GigaAM‑v3** — export/convert from the upstream repo
  <https://github.com/salute-developers/GigaAM> to a sherpa‑onnx int8 CTC `model.int8.onnx`
  (the `is_giga_am` metadata + 64‑mel feature config must be present).
- **TeraTTS sampler** — from the upstream TeraTTS / TeraSpace weights
  <https://github.com/Tera2Space/TeraTTS> · <https://huggingface.co/TeraSpace>.

If you distribute a release, attach these two files as **release assets** (or use Git LFS) rather than
committing them.
