# Kémzy PersonaLive GPU worker — Kaggle

Use the clean notebook at `tools/KEMZY_LIVE_STREAM_RUN_ALL.ipynb`. It pins the upstream PersonaLive revision, reuses mounted model weights or downloads missing files, then starts `personalive_outbound_worker.py`.

## Kaggle setup
- Turn **GPU** on.
- Turn **Internet** on.
- Add Kaggle secret `GPU_WORKER_SECRET`. It must match the Render service's `GPU_WORKER_SECRET` (or `GPU_RENDERER_TOKEN`) value.
- The notebook connects to `https://kemzy-api-production.onrender.com`, which is the Render origin behind the existing Cloudflare gateway used by the Android app.
- Press **Run All** and leave the notebook running.

The worker connects outbound over WebSocket to Render at `/gpu-bridge`; it does not create a public GPU URL or Cloudflare tunnel. PersonaLive is the only renderer used by this run-all workflow.

Kaggle sessions are temporary and quota-limited. When a session ends, run the notebook again. A running worker alone is not proof of a successful render; the final check is that the app sends camera frames and receives generated avatar frames.
