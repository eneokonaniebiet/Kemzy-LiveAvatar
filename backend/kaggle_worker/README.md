# Kémzy FasterLivePortrait GPU worker — Kaggle

This worker connects outbound to the Kémzy Render gateway and runs the official Kémzy renderer fork:

https://github.com/eneokonaniebiet/FasterLivePortrait1

No inbound GPU URL or Cloudflare tunnel is required. The worker needs Internet access and a Kaggle GPU runtime.

Required environment variables:
- KEMZY_RENDER_WS_URL — Render gateway base URL
- GPU_WORKER_SECRET — same secret configured on the Render gateway
- FASTERLIVE_CHECKPOINTS_DIR — optional; directory containing liveportrait_onnx/warping_spade.onnx

Start:

python /kaggle/working/Kemzy-LiveAvatar/backend/kaggle_worker/outbound_worker.py

The worker registers as FasterLivePortrait, accepts source image/video uploads, and returns rendered JPEG frames through the Render broker.

Kaggle GPU sessions are temporary and quota/availability constrained; this is not a 24/7 GPU host.
