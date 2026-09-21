from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path("/kaggle/working/Kemzy-LiveAvatar")
WORKER = ROOT / "backend" / "kaggle_worker" / "outbound_worker.py"

print("=== KÉMZY KAGGLE OUTBOUND GPU WORKER ===", flush=True)
print("This notebook is a transient GPU compute worker.", flush=True)
print("It does NOT create a public GPU URL or Cloudflare tunnel.", flush=True)
print("It connects outbound to the permanent Render gateway.", flush=True)

if not os.getenv("KEMZY_RENDER_WS_URL"):
    raise RuntimeError("Set KEMZY_RENDER_WS_URL to the Render origin, e.g. https://kemzy-liveavatar-api.onrender.com")
if not os.getenv("GPU_WORKER_SECRET"):
    raise RuntimeError("Set GPU_WORKER_SECRET in Kaggle Secrets to the same internal worker secret configured on Render.")

if not ROOT.exists():
    subprocess.run([
        "git", "clone", "--depth", "1", "--branch", "feature/kaggle-gpu-worker",
        "https://github.com/eneokonaniebiet/Kemzy-LiveAvatar.git", str(ROOT)
    ], check=True)

env = os.environ.copy()
env["PYTHONPATH"] = str(ROOT / "backend")
subprocess.run([sys.executable, str(WORKER)], env=env, check=True)
