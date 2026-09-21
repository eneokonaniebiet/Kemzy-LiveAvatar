from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path("/kaggle/working/Kemzy-LiveAvatar")
PORT = int(os.getenv("PORT", "8000"))
APP_DIR = ROOT / "backend" / "zerogpu_space"
CLOUDFLARED = Path("/kaggle/working/cloudflared")

os.environ.setdefault("KEMZY_ALLOW_MODEL_DOWNLOAD", "1")
os.environ.setdefault("KEMZY_SKIP_MODEL_LOAD", "0")
os.environ.setdefault("LIVEPORTRAIT_REPO", "/kaggle/working/LivePortrait")
os.environ.setdefault("LIVEPORTRAIT_WEIGHTS", "/kaggle/working/LivePortrait/pretrained_weights")
os.environ.setdefault("PYTHONPATH", str(APP_DIR))

if not ROOT.exists():
    subprocess.run([
        "git", "clone", "--depth", "1", "--branch", "feature/backend-render-gateway",
        "https://github.com/eneokonaniebiet/Kemzy-LiveAvatar.git", str(ROOT)
    ], check=True)

subprocess.run([
    sys.executable, "-m", "pip", "install", "-q", "onnxruntime-gpu>=1.20,<1.24"
], check=True)

if not CLOUDFLARED.exists():
    url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
    urllib.request.urlretrieve(url, CLOUDFLARED)
    CLOUDFLARED.chmod(0o755)

env = os.environ.copy()
env["PYTHONPATH"] = str(APP_DIR)
server = subprocess.Popen(
    [sys.executable, "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", str(PORT)],
    cwd=str(APP_DIR),
    env=env,
)

ready = False
for _ in range(180):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as r:
            body = r.read().decode()
            print("LOCAL HEALTH:", body, flush=True)
            try:
                health = json.loads(body)
            except Exception:
                health = {}
            if health.get("status") == "ready":
                ready = True
                break
    except Exception:
        pass
    time.sleep(2)

if not ready:
    server.terminate()
    raise RuntimeError("Renderer did not become ready within 6 minutes")

print("\n=== KEMZY PUBLIC GPU WORKER ===", flush=True)
print("Keep this Kaggle session running.", flush=True)
print("The public URL is temporary and changes when the session restarts.", flush=True)

tunnel = subprocess.Popen(
    [str(CLOUDFLARED), "tunnel", "--url", f"http://127.0.0.1:{PORT}", "--no-autoupdate"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
)

for line in iter(tunnel.stdout.readline, ""):
    line = line.rstrip()
    print(line, flush=True)

    match = re.search(r"https://([a-z0-9-]+)\.trycloudflare\.com\\b", line, re.I)
    if match:
        public_url = f"https://{match.group(1)}.trycloudflare.com"
        print("\n=== GPU_RENDERER_URL ===", flush=True)
        print(public_url, flush=True)
