# Kémzy free GPU worker — Kaggle

This is the no-billing bridge for the existing Render gateway and the existing Kémzy LivePortrait backend.

Run the existing Kémzy v6-style Kaggle notebook with GPU T4 x2 and Internet enabled. The notebook starts the outbound worker from:

`backend/kaggle_worker/start_worker.py`

The worker connects **outbound** to the permanent Render gateway at:

`/gpu-bridge`

It does not create a public GPU URL and it does not require the Android app to know a temporary tunnel URL.

Required Kaggle Secrets:
- `KEMZY_RENDER_WS_URL` — the Render origin, for example `https://kemzy-liveavatar-api.onrender.com`
- `GPU_WORKER_SECRET` — the same private worker secret configured on Render

Once the worker registers, Render exposes:
- `GET /ready` — reports whether a Kaggle GPU worker is online
- `POST /v1/sessions` — creates a GPU-backed session
- `POST /v1/sessions/{id}/source` — uploads the avatar source
- `WS /v1/stream/{id}` — accepts camera/motion packets and returns rendered frames

The Android app starts the user's authenticated Kaggle notebook, waits for `/ready`, then uses the existing Render/Cloudflare gateway. Kaggle/Render/tunnel addresses are kept out of the app UI.

This remains a temporary free GPU worker: Kaggle sessions are time-limited and GPU availability is quota/availability constrained.
