from __future__ import annotations

import base64
import io
import os
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

import torch
import websocket
from PIL import Image

ROOT = Path("/kaggle/working/Kemzy-LiveAvatar")
PERSONALIVE_ROOT = Path("/kaggle/working/PersonaLive")
BACKEND = ROOT / "backend"
PINNED_PERSONALIVE = "abdd112e01dcf7d89122c2e5efa29fcff0669740"
RENDER_URL = os.environ["KEMZY_RENDER_WS_URL"].rstrip("/")
WORKER_SECRET = os.environ["GPU_WORKER_SECRET"]
WORKER_ID = os.getenv("KEMZY_GPU_WORKER_ID", f"kaggle-{uuid.uuid4().hex[:12]}")
MODEL_DIR = os.getenv("MODEL_DIR", "/kaggle/working/PersonaLive/pretrained_weights")

os.environ["MODEL_DIR"] = MODEL_DIR
os.environ["PERSONALIVE_COMMIT"] = PINNED_PERSONALIVE
sys.path.insert(0, str(PERSONALIVE_ROOT))
sys.path.insert(0, str(BACKEND))

PIPELINE = None
APP_ARGS = None
CURRENT_REFERENCE_SESSION = None
SESSIONS = {}
PIPELINE_LOCK = threading.Lock()


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print("$", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True)


def ensure_personalive() -> None:
    if not PERSONALIVE_ROOT.exists():
        run(["git", "clone", "https://github.com/GVCLab/PersonaLive.git", str(PERSONALIVE_ROOT)])
        run(["git", "checkout", PINNED_PERSONALIVE], cwd=PERSONALIVE_ROOT)
    else:
        run(["git", "fetch", "--depth", "1", "origin", PINNED_PERSONALIVE], cwd=PERSONALIVE_ROOT)
        run(["git", "checkout", PINNED_PERSONALIVE], cwd=PERSONALIVE_ROOT)

    req = PERSONALIVE_ROOT / "requirements_base.txt"
    run([sys.executable, "-m", "pip", "install", "-q", "-r", str(req)])
    run([sys.executable, "-m", "pip", "install", "-q", "websocket-client>=1.8,<2"])


def load_pipeline() -> None:
    global PIPELINE, APP_ARGS
    os.chdir(PERSONALIVE_ROOT)
    from webcam.config import Args
    from webcam.vid2vid import Pipeline

    APP_ARGS = Args(
        host="127.0.0.1",
        port=7860,
        reload=False,
        mode="default",
        max_queue_size=4,
        timeout=0.0,
        safety_checker=False,
        taesd=True,
        ssl_certfile=None,
        ssl_keyfile=None,
        debug=False,
        acceleration=os.getenv("ACCELERATION", "xformers"),
        engine_dir=os.getenv("ENGINE_DIR", "engines"),
        config_path=os.getenv("PERSONALIVE_CONFIG", "./configs/prompts/personalive_online.yaml"),
    )
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA GPU is required")
    torch.cuda.set_device(0)
    PIPELINE = Pipeline(APP_ARGS, torch.device("cuda:0"))
    print("PERSONALIVE_PIPELINE_READY", flush=True)
    print("GPU:", torch.cuda.get_device_name(0), flush=True)


def first_video_frame(data: bytes) -> Image.Image:
    import tempfile
    import cv2

    with tempfile.NamedTemporaryFile(suffix=".mp4") as handle:
        handle.write(data)
        handle.flush()
        cap = cv2.VideoCapture(handle.name)
        ok, frame = cap.read()
        cap.release()
    if not ok:
        raise ValueError("Could not decode the uploaded video")
    frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    return Image.fromarray(frame)


def decode_source(data: bytes, content_type: str) -> Image.Image:
    if content_type.startswith("video/"):
        return first_video_frame(data)
    return Image.open(io.BytesIO(data)).convert("RGB")


def prepare_source(payload: dict) -> dict:
    session_id = payload["session_id"]
    raw = base64.b64decode(payload["data_base64"]) if payload.get("data_base64") else None
    if raw:
        image = decode_source(raw, payload.get("content_type", "image/jpeg"))
        SESSIONS[session_id] = image
    elif session_id not in SESSIONS:
        raise ValueError("Source image has not been uploaded")
    return {"status": "ready", "worker_id": WORKER_ID, "session_id": session_id}


def render_frame(payload: dict) -> dict:
    session_id = payload["session_id"]
    if session_id not in SESSIONS:
        raise ValueError("Unknown source session")

    images = payload.get("driving_images") or []
    if not images:
        raise ValueError("driving_images must contain camera frame(s)")

    with PIPELINE_LOCK:
        PIPELINE.fuse_reference(SESSIONS[session_id])
        for encoded in images:
            driving = base64.b64decode(encoded)
            params = PIPELINE.InputParams()
            # PersonaLive's online pipeline accepts the encoded camera frame
            # through bytes_to_tensor, exactly like its native online server.
            from webcam.util import bytes_to_tensor
            params.image = bytes_to_tensor(driving)
            PIPELINE.accept_new_params(params)

        deadline = time.monotonic() + float(os.getenv("RENDER_TIMEOUT_SECONDS", "120"))
        generated = []
        while time.monotonic() < deadline:
            generated = PIPELINE.produce_outputs()
            if generated:
                break
            time.sleep(0.01)

        if not generated:
            raise TimeoutError("PersonaLive produced no output frame before timeout")

        output = io.BytesIO()
        generated[0].convert("RGB").save(output, format="JPEG", quality=85)
        jpeg = output.getvalue()
        return {
            "status": "rendered",
            "worker_id": WORKER_ID,
            "mime_type": "image/jpeg",
            "image_base64": base64.b64encode(jpeg).decode("ascii"),
            "generated_frames": len(generated),
        }


def process(payload: dict) -> dict:
    action = payload.get("action")
    if action == "PREPARE_SOURCE":
        if "data_base64" not in payload:
            return {"status": "ready", "worker_id": WORKER_ID}
        return prepare_source(payload)
    if action == "RENDER_FRAME":
        return render_frame(payload)
    raise ValueError(f"Unknown action: {action}")


def run_connection() -> None:
    def on_open(ws):
        ws.send(__import__("json").dumps({
            "type": "register",
            "worker_id": WORKER_ID,
            "gpu": torch.cuda.get_device_name(0),
            "cuda": torch.version.cuda,
            "renderer": "PersonaLive",
            "personalive_commit": PINNED_PERSONALIVE,
        }))
        print("KAGGLE_GPU_WORKER_ONLINE", flush=True)

    def on_message(ws, message):
        import json
        msg = json.loads(message)
        if msg.get("type") == "registered":
            print("BROKER_REGISTERED", flush=True)
            return
        if msg.get("type") != "job":
            return
        job_id = msg.get("jobId")
        try:
            result = process(msg.get("data", {}))
        except Exception as exc:
            result = {"status": "failed", "error": f"{type(exc).__name__}: {exc}"}
        ws.send(json.dumps({"type": "result", "jobId": job_id, "result": result}))

    def on_error(ws, error):
        print("BROKER_SOCKET_ERROR:", error, flush=True)

    def on_close(ws, code, reason):
        print("BROKER_SOCKET_CLOSED:", code, reason, flush=True)

    def heartbeat():
        while True:
            time.sleep(15)
            try:
                ws.send(__import__("json").dumps({"type": "heartbeat"}))
            except Exception:
                return

    while True:
        ws = websocket.WebSocketApp(
            RENDER_URL + "/gpu-bridge",
            header=[f"X-Worker-Auth: {WORKER_SECRET}"],
            on_open=on_open,
            on_message=on_message,
            on_error=on_error,
            on_close=on_close,
        )
        hb = threading.Thread(target=heartbeat, daemon=True)
        hb.start()
        ws.run_forever(ping_interval=20, ping_timeout=10)
        time.sleep(3)


if __name__ == "__main__":
    print("=== KÉMZY KAGGLE OUTBOUND GPU WORKER ===", flush=True)
    print("CUDA:", torch.cuda.is_available(), flush=True)
    ensure_personalive()
    load_pipeline()
    run_connection()
