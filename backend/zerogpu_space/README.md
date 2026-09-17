---
title: Kémzy Neural Renderer
emoji: 🎭
colorFrom: indigo
colorTo: purple
sdk: gradio
python_version: 3.12
app_file: app.py
suggested_hardware: zero-a10g
---

# Kémzy Neural Renderer — GPU

This directory contains the GPU renderer used behind the Kémzy Render gateway. It is deliberately separate from the Android app and gateway.

## Model source

The renderer uses the official KlingAIResearch LivePortrait PyTorch implementation as the neural rendering engine. The upstream pipeline uses appearance extraction, motion extraction, warping, SPADE generation, and stitching/retargeting models. Kémzy calls those real pipeline components through the upstream wrapper rather than synthesizing frames locally.

No LivePortrait weights are committed to this repository and no weights are sent to the Android device.

## GPU model provisioning

The renderer expects these files under `LIVEPORTRAIT_WEIGHTS`:

```text
liveportrait/base_models/appearance_feature_extractor.pth
liveportrait/base_models/motion_extractor.pth
liveportrait/base_models/warping_module.pth
liveportrait/base_models/spade_generator.pth
liveportrait/retargeting_models/stitching_retargeting_module.pth
```

`LIVEPORTRAIT_REPO` defaults to `/tmp/LivePortrait` and `LIVEPORTRAIT_WEIGHTS` defaults to `$LIVEPORTRAIT_REPO/pretrained_weights`.

Automatic Hugging Face model download is **disabled by default**. Set `KEMZY_ALLOW_MODEL_DOWNLOAD=1` only on the GPU host if that host is intentionally responsible for provisioning the weights. The Android client never downloads these files.

The renderer fails readiness if the required checkpoints are absent or if the loaded upstream wrapper does not select CUDA.

## Source contract

The first backend contract expects a single-face source image and prepares it at 256×256. The Android source editor will eventually provide the crop. The backend stores compact source features by a short-lived handle.

Motion is represented as:

- `pose`: `[pitch, yaw, roll]`, normalized around zero and converted to LivePortrait-compatible degrees server-side.
- `expression`: 63 values representing a 21×3 LivePortrait expression delta.
- `landmarks`: optional driver landmarks reserved for tracker/retargeting integration.

The render path follows LivePortrait's relative image-source motion formulation: source canonical keypoints are rotated by the incoming driver pose, source expression is combined with the incoming expression delta, then the upstream stitching module and warp/decoder generate the frame.

## Gradio API

- `/prepare_source` → returns a source handle.
- `/render_motion` → returns a real neural rendered frame.
- `/health` → reports renderer readiness.

The renderer raises an error when the model is unavailable. It never returns a generated placeholder image.

## Local contract test

```bash
KEMZY_SKIP_MODEL_LOAD=1 pytest -q test_contract.py
```

## Kinesis deployment

Build and deploy the GPU image from the Kémzy repository. Before accepting the deployment, verify GPU visibility, CUDA availability, required checkpoint presence, and successful model startup. Do not rebuild or download the Android artifact until the GPU smoke test passes.

## Acceptance gate

Do not call the GPU renderer production-ready until an external smoke test has demonstrated:

1. GPU/CUDA is visible;
2. all five required LivePortrait checkpoint groups load successfully;
3. `/health` reports ready;
4. `/prepare_source` accepts a real portrait;
5. two different motion inputs produce two rendered frames;
6. the frames are measurably different;
7. no model/runtime error occurs;
8. the Kémzy gateway can receive the rendered frame through `gradio_client`.
