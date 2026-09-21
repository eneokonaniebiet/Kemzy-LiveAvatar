# Kémzy Kaggle GPU worker

This path intentionally does not use ComfyUI and does not create a public GPU URL.

Architecture:

Kémzy Android -> Cloudflare Worker -> Render gateway -> outbound WebSocket -> Kaggle T4 -> PersonaLive -> Render -> Cloudflare -> Android

## Kaggle secrets

Add these Kaggle notebook secrets:

- KEMZY_RENDER_WS_URL = the Render gateway origin
- GPU_WORKER_SECRET = the same internal worker secret configured on Render

Do not commit either secret.

## Run

Enable Internet and GPU in the Kaggle notebook, then run:

    from pathlib import Path
    import subprocess, sys
    repo = Path('/kaggle/working/Kemzy-LiveAvatar')
    if not repo.exists():
        subprocess.run([
            'git', 'clone', '--depth', '1',
            '--branch', 'feature/backend-gateway',
            'https://github.com/eneokonaniebiet/Kemzy-LiveAvatar.git',
            str(repo)
        ], check=True)
    subprocess.run([
        sys.executable,
        str(repo / 'backend/kaggle_worker/start_worker.py')
    ], check=True)

The worker clones the pinned PersonaLive commit, installs its native requirements, loads the CUDA pipeline once, and registers to Render.

If the Kaggle session restarts, run the cell again. The worker reconnects automatically while the process remains alive.

## Important

No trycloudflare.com URL is required.
No GPU URL is placed in the Android APK.
No ComfyUI is required.
The Render gateway remains the permanent broker/control plane.

The worker accepts source images or videos (the first video frame becomes the PersonaLive reference) and live camera JPEG frames through the driving_images field.
