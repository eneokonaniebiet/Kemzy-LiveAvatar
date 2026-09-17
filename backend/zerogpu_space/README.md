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

# Kémzy Neural Renderer — ZeroGPU

This directory is the first GPU adapter for Kémzy àvátâr. It is deliberately separate from the Render gateway and Android app.

## GPU model

The intended deployment is a Hugging Face Gradio Space using ZeroGPU. Current Hugging Face documentation says free personal accounts in good standing can host up to two ZeroGPU Spaces; ZeroGPU is Gradio-only and has finite daily GPU quotas. The exact eligibility is controlled by Hugging Face at Space-creation time.

## Model source

The adapter downloads the LivePortrait PyTorch weights into the Space at runtime from `KlingTeam/LivePortrait`. No weights are committed to this repository and no weights are sent to the Android device.

The renderer intentionally does not download or use InsightFace/inswapper weights. LivePortrait's published license notes that its InsightFace detection models have separate non-commercial restrictions, so Kémzy's commercial path must keep those models out unless separately licensed.

## Source contract

The first backend contract expects a single-face source image already cropped to 256×256. The Android `SourceEditor` will eventually provide that crop. The backend stores compact source features by a short-lived handle.

Motion is represented as:

- `pose`: `[pitch, yaw, roll]`, normalized around zero and converted to degrees server-side.
- `expression`: 63 values representing the 21×3 LivePortrait expression/keypoint delta.
- `landmarks`: optional driver landmarks reserved for later tracker integration.

## Gradio API

- `/prepare_source` → returns a source handle.
- `/render_motion` → returns a real neural rendered frame.
- `/health` → reports renderer readiness.

The renderer raises an error when the model is unavailable. It never returns a generated placeholder image.

## Local contract test

```bash
KEMZY_SKIP_MODEL_LOAD=1 pytest -q test_contract.py
```

## Space deployment

Copy the contents of `backend/zerogpu_space` into a new Hugging Face Gradio Space and select ZeroGPU hardware. This repository currently has no Hugging Face write connection, so the Space cannot be created from the Kémzy GitHub connector itself.

After the Space is live, its Gradio API URL can be connected to the Render gateway using `GPU_RENDERER_URL`.

## Acceptance gate

Do not call the GPU renderer production-ready until an external smoke test has demonstrated:

1. model startup succeeds;
2. `/prepare_source` accepts a real portrait;
3. two different motion inputs produce two rendered frames;
4. the frames are measurably different;
5. no model/runtime error occurs.
