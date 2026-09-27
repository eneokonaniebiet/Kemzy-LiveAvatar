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

import cv2
import numpy as np
import torch
import websocket
from omegaconf import OmegaConf

# FasterLivePortrait is the renderer used by the existing Kaggle work.
# The worker reuses an already-populated checkpoint/engine directory when
# present; it does NOT download or rebuild models during normal startup.
REPO_URL = "https://github.com/eneokonaniebiet/FasterLivePortrait1.git"
REPO_REF = "replicate-test"
ROOT_CANDIDATES = [
    Path(os.getenv("FLP_ROOT", "")) if os.getenv("FLP_ROOT") else None,
    Path("/kaggle/working/FasterLivePortrait1"),
    Path("/kaggle/working/FasterLivePortrait"),
]
ROOT = next((p for p in ROOT_CANDIDATES if p and p.exists()), ROOT_CANDIDATES[1])
CHECKPOINT_CANDIDATES = [
    Path(os.getenv("FLIP_CHECKPOINT_DIR", "")) if os.getenv("FLIP_CHECKPOINT_DIR") else None,
    ROOT / "checkpoints",
    Path("/kaggle/working/checkpoints"),
]
CHECKPOINT_DIR = next((p for p in CHECKPOINT_CANDIDATES if p and p.exists()), ROOT / "checkpoints")

BACKEND = Path("/kaggle/working/Kemzy-LiveAvatar/backend")
RENDER_URL = os.environ["KEMZY_RENDER_WS_URL"].rstrip("/")
WORKER_SECRET = os.environ["GPU_WORKER_SECRET"]
WORKER_ID = os.getenv("KEMZY_GPU_WORKER_ID", f"kaggle-flp-{uuid.uuid4().hex[:12]}")

os.environ["FLIP_CHECKPOINT_DIR"] = str(CHECKPOINT_DIR)
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(BACKEND))

PIPELINE = None
CURRENT_SOURCE = {}
RENDERED_SESSIONS = set()
PIPELINE_LOCK = threading.Lock()


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print("$", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True)


def ensure_fasterliveportrait_code() -> None:
    if ROOT.exists() and (ROOT / "src").exists():
        print("Using existing FasterLivePortrait code:", ROOT, flush=True)
        return
    ROOT.parent.mkdir(parents=True, exist_ok=True)
    run(["git", "clone", "--depth", "1", "--branch", REPO_REF, REPO_URL, str(ROOT)])
    print("FasterLivePortrait code restored; existing checkpoints are kept separate.", flush=True)


def load_pipeline() -> None:
    global PIPELINE
    ensure_fasterliveportrait_code()

    cfg_path = ROOT / "configs" / "trt_infer.yaml"
    if not cfg_path.exists():
        raise RuntimeError(f"Missing FasterLivePortrait config: {cfg_path}")
    if not CHECKPOINT_DIR.exists():
        raise RuntimeError(
            f"FasterLivePortrait checkpoints/engines are not present at {CHECKPOINT_DIR}. "
            "The existing Kaggle model directory must be mounted/restored before Run All."
        )

    cfg = OmegaConf.load(str(cfg_path))
    for section in ("models", "animal_models"):
        if section not in cfg:
            continue
        for name in cfg[section]:
            model_path = cfg[section][name].get("model_path")
            if isinstance(model_path, str):
                cfg[section][name].model_path = model_path.replace(
                    "./checkpoints", str(CHECKPOINT_DIR)
                )
            elif model_path is not None:
                cfg[section][name].model_path = [
                    p.replace("./checkpoints", str(CHECKPOINT_DIR))
                    for p in model_path
                ]

    cfg.infer_params.flag_pasteback = True

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA GPU is required for FasterLivePortrait")
    torch.cuda.set_device(0)

    # This constructor loads the existing TensorRT engines/checkpoints.
    PIPELINE = __import__(
        "src.pipelines.faster_live_portrait_pipeline",
        fromlist=["FasterLivePortraitPipeline"],
    ).FasterLivePortraitPipeline(cfg=cfg, is_animal=False)

    print("FASTERLIVEPORTRAIT_PIPELINE_READY", flush=True)
    print("GPU:", torch.cuda.get_device_name(0), flush=True)
    print("CHECKPOINT_DIR:", CHECKPOINT_DIR, flush=True)


def decode_source(data: bytes, content_type: str = "image/jpeg") -> np.ndarray:
    if content_type.startswith("video/"):
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as f:
            f.write(data)
            path = f.name
        try:
            cap = cv2.VideoCapture(path)
            ok, frame = cap.read()
            cap.release()
        finally:
            try:
                os.unlink(path)
            except OSError:
                pass
        if not ok or frame is None:
            raise ValueError("Could not decode source video")
        return frame
    image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("Could not decode source image")
    return image


def prepare_source(payload: dict) -> dict:
    session_id = payload["session_id"]
    raw = base64.b64decode(payload["data_base64"])
    # Keep source bytes only in this worker's temporary memory/session state.
    source = decode_source(raw, payload.get("content_type", "image/jpeg"))
    with PIPELINE_LOCK:
        import tempfile
        suffix = ".mp4" if payload.get("content_type", "").startswith("video/") else ".jpg"
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as f:
            f.write(raw)
            source_path = f.name
        try:
            ok = PIPELINE.prepare_source(source_path, realtime=True)
        finally:
            try:
                os.unlink(source_path)
            except OSError:
                pass
    if not ok or not PIPELINE.src_imgs or not PIPELINE.src_infos:
        raise ValueError("No usable face was detected in source image")
    CURRENT_SOURCE[session_id] = True
    RENDERED_SESSIONS.discard(session_id)
    return {"status": "ready", "worker_id": WORKER_ID, "session_id": session_id}


def render_frame(payload: dict) -> dict:
    session_id = payload["session_id"]
    if not CURRENT_SOURCE.get(session_id):
        raise ValueError("Source image has not been prepared")

    images = payload.get("driving_images") or []
    if not images:
        raise ValueError("driving_images must contain a camera JPEG")

    raw = base64.b64decode(images[0])
    frame = decode_source(raw)

    with PIPELINE_LOCK:
        _, output_crop, output_full, _ = PIPELINE.run(
            frame,
            PIPELINE.src_imgs[0],
            PIPELINE.src_infos[0],
            first_frame=session_id not in RENDERED_SESSIONS,
            realtime=False,
        )

    RENDERED_SESSIONS.add(session_id)
    if output_crop is None or output_full is None:
        raise ValueError("No face detected in driving frame")

    rgb = cv2.cvtColor(output_full, cv2.COLOR_RGB2BGR)
    ok, encoded = cv2.imencode(".jpg", rgb, [int(cv2.IMWRITE_JPEG_QUALITY), 82])
    if not ok:
        raise RuntimeError("JPEG encoding failed")
    jpeg = encoded.tobytes()
    return {
        "status": "rendered",
        "worker_id": WORKER_ID,
        "mime_type": "image/jpeg",
        "image_base64": base64.b64encode(jpeg).decode("ascii"),
    }


def process(payload: dict) -> dict:
    action = payload.get("action")
    if action == "CREATE_SESSION":
        return {
            "status": "ready",
            "worker_id": WORKER_ID,
            "session_id": payload.get("session_id"),
            "renderer": "FasterLivePortrait",
        }
    if action == "PREPARE_SOURCE":
        return prepare_source(payload)
    if action == "RENDER_FRAME":
        return render_frame(payload)
    raise ValueError(f"Unknown action: {action}")


def run_connection() -> None:
    def on_open(ws):
        import json
        ws.send(json.dumps({
            "type": "register",
            "worker_id": WORKER_ID,
            "gpu": torch.cuda.get_device_name(0),
            "cuda": torch.version.cuda,
            "renderer": "FasterLivePortrait",
            "renderer_repo": "eneokonaniebiet/FasterLivePortrait1",
            "renderer_ref": REPO_REF,
        }))
        print("KAGGLE_FASTERLIVEPORTRAIT_WORKER_ONLINE", flush=True)

        def heartbeat():
            while True:
                time.sleep(15)
                try:
                    ws.send(json.dumps({"type": "heartbeat", "worker_id": WORKER_ID}))
                except Exception:
                    return

        threading.Thread(target=heartbeat, daemon=True).start()

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
    print("=== KÉMZY FASTERLIVEPORTRAIT KAGGLE GPU WORKER ===", flush=True)
    print("CUDA:", torch.cuda.is_available(), flush=True)
    load_pipeline()
    run_connection()
