from __future__ import annotations

import base64
import io
import os
import sys
import time
import uuid
from collections import deque
from pathlib import Path

import torch
import websocket
from PIL import Image

# This worker runs inside the existing Kaggle PersonaLive environment.
# It never creates a public GPU URL.
ROOT = Path(os.getenv("PERSONALIVE_ROOT", "/kaggle/working/PersonaLive"))
RENDER_URL = os.environ["KEMZY_RENDER_WS_URL"].rstrip("/")
if RENDER_URL.endswith("/gpu-bridge"):
    RENDER_URL = RENDER_URL[:-len("/gpu-bridge")]
WORKER_SECRET = os.environ["GPU_WORKER_SECRET"]
WORKER_ID = os.getenv("KEMZY_GPU_WORKER_ID", f"personalive-kaggle-{uuid.uuid4().hex[:10]}")

if not ROOT.exists():
    raise FileNotFoundError(f"PersonaLive root not found: {ROOT}")

os.chdir(ROOT)
sys.path.insert(0, str(ROOT))
sys.argv = ["personalive_outbound_worker.py"]

from webcam.config import Args
from webcam.util import bytes_to_tensor, pil_to_frame
from webcam.vid2vid import Pipeline


def build_args() -> Args:
    return Args(
        host="0.0.0.0",
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
        acceleration=os.getenv("ACCELERATION", "none"),
        engine_dir="engines",
        config_path=os.getenv(
            "PERSONALIVE_CONFIG",
            "./configs/prompts/personalive_online.yaml",
        ),
    )


PIPELINE = None
PIPELINE_LOCK = None
ACTIVE_SESSION = ""
FRAME_HISTORY: deque[bytes] = deque(maxlen=4)


def ensure_pipeline(session_id: str, reference_bytes: bytes) -> None:
    global PIPELINE, ACTIVE_SESSION, FRAME_HISTORY

    if PIPELINE is not None:
        try:
            PIPELINE.close()
        except Exception:
            pass

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA GPU is required for PersonaLive")

    print("PERSONA_LIVE_PIPELINE_START", flush=True)
    PIPELINE = Pipeline(build_args(), torch.device("cuda:0"))

    reference = Image.open(io.BytesIO(reference_bytes)).convert("RGB")
    PIPELINE.fuse_reference(reference)

    ACTIVE_SESSION = session_id
    FRAME_HISTORY.clear()
    print("PERSONA_LIVE_PIPELINE_READY", flush=True)
    print("GPU:", torch.cuda.get_device_name(0), flush=True)


def create_session(data: dict) -> dict:
    return {
        "status": "ready",
        "worker_id": WORKER_ID,
        "session_id": str(data["session_id"]),
    }


def prepare_source(data: dict) -> dict:
    session_id = str(data["session_id"])
    encoded = data.get("data_base64")
    if not encoded:
        raise ValueError("Source image data is required")

    raw = base64.b64decode(encoded)
    ensure_pipeline(session_id, raw)

    return {
        "status": "ready",
        "worker_id": WORKER_ID,
        "session_id": session_id,
        "renderer": "PersonaLive",
    }


def render_frame(data: dict) -> dict:
    session_id = str(data["session_id"])
    if PIPELINE is None or ACTIVE_SESSION != session_id:
        raise ValueError("Source is not prepared for this session")

    images = data.get("driving_images") or []
    if not images:
        raise ValueError("driving_images must contain at least one camera frame")

    raw = base64.b64decode(images[0])
    if not raw:
        raise ValueError("Driving frame is empty")

    # PersonaLive's online pipeline consumes four driving frames at a time.
    # The Render broker dispatches one job at a time, so maintain a bounded
    # sliding window and pad the first frames with the newest frame.
    FRAME_HISTORY.append(raw)
    batch = list(FRAME_HISTORY)
    while len(batch) < 4:
        batch.insert(0, raw)

    for frame_bytes in batch[-4:]:
        params = PIPELINE.InputParams()
        params.image = bytes_to_tensor(frame_bytes)
        PIPELINE.accept_new_params(params)

    deadline = time.monotonic() + float(os.getenv("RENDER_TIMEOUT_SECONDS", "120"))
    generated = []

    while time.monotonic() < deadline:
        generated = PIPELINE.produce_outputs()
        if generated:
            break
        time.sleep(0.01)

    if not generated:
        raise TimeoutError("PersonaLive produced no frame before timeout")

    # Return the newest generated frame to the Render broker.
    jpeg = pil_to_frame(generated[-1])

    return {
        "status": "rendered",
        "worker_id": WORKER_ID,
        "renderer": "PersonaLive",
        "mime_type": "image/jpeg",
        "image_base64": base64.b64encode(jpeg).decode("ascii"),
    }


def process(data: dict) -> dict:
    action = data.get("action")
    if action == "CREATE_SESSION":
        return create_session(data)
    if action == "PREPARE_SOURCE":
        return prepare_source(data)
    if action == "RENDER_FRAME":
        return render_frame(data)
    raise ValueError(f"Unknown action: {action}")


def run_connection() -> None:
    import json

    def on_open(ws):
        ws.send(json.dumps({
            "type": "register",
            "worker_id": WORKER_ID,
            "gpu": torch.cuda.get_device_name(0),
            "cuda": torch.version.cuda,
            "renderer": "PersonaLive",
            "backend": "PersonaLive",
            "personalive_commit": "abdd112e01dcf7d89122c2e5efa29fcff0669740",
        }))
        print("KEMZY_PERSONALIVE_GPU_WORKER_ONLINE", flush=True)

    def on_message(ws, message):
        try:
            msg = json.loads(message)
            if msg.get("type") == "registered":
                print("BROKER_REGISTERED", flush=True)
                return
            if msg.get("type") != "job":
                return

            try:
                result = process(msg.get("data", {}))
            except Exception as exc:
                print("JOB_ERROR:", repr(exc), flush=True)
                result = {
                    "status": "failed",
                    "error": f"{type(exc).__name__}: {exc}",
                    "renderer": "PersonaLive",
                }

            ws.send(json.dumps({
                "type": "result",
                "jobId": msg.get("jobId"),
                "result": result,
            }))
        except Exception as exc:
            print("MESSAGE_ERROR:", repr(exc), flush=True)

    def on_error(ws, error):
        print("BROKER_SOCKET_ERROR:", error, flush=True)

    def on_close(ws, code, reason):
        print("BROKER_SOCKET_CLOSED:", code, reason, flush=True)

    while True:
        ws = websocket.WebSocketApp(
            RENDER_URL + "/gpu-bridge",
            header=[f"X-Worker-Auth: {WORKER_SECRET}"],
            on_open=on_open,
            on_message=on_message,
            on_error=on_error,
            on_close=on_close,
        )
        ws.run_forever(ping_interval=20, ping_timeout=10)
        time.sleep(3)


if __name__ == "__main__":
    print("=== KÉMZY PERSONA LIVE OUTBOUND GPU WORKER ===", flush=True)
    print("CUDA:", torch.cuda.is_available(), flush=True)
    run_connection()
