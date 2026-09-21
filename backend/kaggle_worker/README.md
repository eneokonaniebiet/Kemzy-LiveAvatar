# Kémzy free GPU worker — Kaggle

This is the no-billing bridge for the existing Render gateway.

Kaggle currently provides free GPU notebook sessions, including T4 x2; availability and quotas are limited.

Run in a Kaggle Notebook with GPU T4 x2 and Internet enabled:

git clone --depth 1 --branch feature/backend-render-gateway https://github.com/eneokonaniebiet/Kemzy-LiveAvatar.git /kaggle/working/Kemzy-LiveAvatar
pip install -q -r /kaggle/working/Kemzy-LiveAvatar/backend/zerogpu_space/requirements.txt
python /kaggle/working/Kemzy-LiveAvatar/backend/kaggle_worker/start_worker.py

The script starts the existing Kémzy LivePortrait FastAPI renderer and a Cloudflare Quick Tunnel. It prints a temporary https://...trycloudflare.com URL.

Set that URL as GPU_RENDERER_URL on the existing Render service kemzy-liveavatar-api.

This is a free temporary GPU worker, not a 24/7 GPU. Kaggle sessions are time-limited and free GPU capacity is quota/availability constrained.