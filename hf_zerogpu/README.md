---
title: Kemzy PersonaLive ZeroGPU
emoji: 🎭
colorFrom: purple
colorTo: pink
sdk: gradio
python_version: "3.12"
app_file: app.py
---

# Kémzy PersonaLive ZeroGPU

GPU proof Space for the Kémzy LiveAvatar backend.

This Space pins PersonaLive to `abdd112e01dcf7d89122c2e5efa29fcff0669740` and exposes a Gradio API for a real CUDA render smoke test.

The permanent Android endpoint remains the Kémzy Cloudflare Worker. This Space is a GPU renderer test/backend target; it must not be embedded directly in the APK.

Important: Hugging Face ZeroGPU is shared/on-demand and has daily free-account GPU quotas. It is not a guaranteed 24/7 GPU server.
