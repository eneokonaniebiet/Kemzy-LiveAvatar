from __future__ import annotations

import base64
import io
import os
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from pathlib import Path

import cv2
import numpy as np
import torch
import websocket
from PIL import Image

ROOT = Path("/kaggle/working/FasterLivePortrait1")
RENDER_URL = os.environ["KEMZY_RENDER_WS_URL"].rstrip("/")
WORKER_SECRET = os.environ["GPU_WORKER_SECRET"]
WORKER_ID = os.getenv("KEMZY_GPU_WORKER_ID", f"kaggle-{uuid.uuid4().hex[:12]}")

PIPELINE = None
SESSIONS: dict[str, dict] = {}
PIPELINE_LOCK = threading.Lock()


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print("$", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True)


def discover_checkpoint_dir() -> Path:
    explicit = os.getenv("FASTERLIVE_CHECKPOINTS_DIR")
    if explicit and (Path(explicit) / "liveportrait_onnx").exists():
        return Path(explicit)

    candidates = [
        Path("/kaggle/input/fasterliveportrait/checkpoints"),
        Path("/kaggle/input/faster-live-portrait/checkpoints"),
        Path("/kaggle/input/fasterliveportrait1/checkpoints"),
        Path("/kaggle/input/kemzy-fasterliveportrait/checkpoints"),
    ]
    for p in candidates:
        if (p / "liveportrait_onnx" / "warping_spade.onnx").exists():
            return p

    for root in Path("/kaggle/input").glob("*"):
        for p in (root, root / "checkpoints", root / "FasterLivePortrait" / "checkpoints"):
            if (p / "liveportrait_onnx" / "warping_spade.onnx").exists():
                return p

    raise FileNotFoundError(
        "FasterLivePortrait checkpoints not found. Set FASTERLIVE_CHECKPOINTS_DIR "
        "to the directory containing liveportrait_onnx/warping_spade.onnx."
    )


def link_checkpoints(checkpoint_dir: Path) -> None:
    target = ROOT / "checkpoints"
    if target.exists() or target.is_symlink():
        if target.is_symlink() and target.resolve() == checkpoint_dir.resolve():
            return
        if target.is_dir() and not any(target.iterdir()):
            target.rmdir()
        else:
            import shutil
            shutil.rmtree(target)
    target.symlink_to(checkpoint_dir, target_is_directory=True)
    print("FASTERLIVE_CHECKPOINTS:", checkpoint_dir, flush=True)


def ensure_fasterlive() -> None:
    if not ROOT.exists():
        run(["git", "clone", "--depth", "1", "https://github.com/eneokonaniebiet/FasterLivePortrait1.git", str(ROOT)])
    else:
        run(["git", "fetch", "--depth", "1", "origin", "main"], cwd=ROOT)
        run(["git", "reset", "--hard", "origin/main"], cwd=ROOT)

    checkpoint_dir = discover_checkpoint_dir()
    link_checkpoints(checkpoint_dir)

    req = ROOT / "requirements.txt"
    lines = []
    skip_prefixes = (
        "torch", "torchvision", "gradio", "huggingface_hub", "kokoro",
        "phonemizer", "misaki", "pycuda",
    )
    for line in req.read_text(encoding="utf-8").splitlines():
        s = line.strip().lower()
        if not s or s.startswith("#"):
            continue
        if s.startswith(skip_prefixes):
            continue
        lines.append(line)
    filtered = ROOT / "requirements-kemzy-kaggle.txt"
    filtered.write_text("\n".join(lines) + "\n", encoding="utf-8")

    run([sys.executable, "-m", "pip", "install", "-q", "-r", str(filtered)])
    run([sys.executable, "-m", "pip", "install", "-q", "onnxruntime-gpu>=1.20,<2", "websocket-client>=1.8,<2"])


def load_pipeline() -> None:
    global PIPELINE
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA GPU is required for FasterLivePortrait")

    torch.cuda.set_device(0)
    os.chdir(ROOT)

    from omegaconf import OmegaConf
    from src.pipelines.faster_live_portrait_pipeline import FasterLivePortraitPipeline

    cfg = OmegaConf.load(ROOT / "configs/onnx_infer.yaml")
    cfg.infer_params.flag_pasteback = False
    cfg.infer_params.flag_do_crop = True
    cfg.infer_params.flag_stitching = True
    cfg.infer_params.flag_relative_motion = True
    cfg.infer_params.flag_crop_driving_video = False

    PIPELINE = FasterLivePortraitPipeline(cfg=cfg, is_animal=False)
    print("FASTERLIVE_PIPELINE_READY", flush=True)
    print("GPU:", torch.cuda.get_device_name(0), flush=True)
    print("CUDA:", torch.version.cuda, flush=True)


def decode_source_to_image(raw: bytes, content_type: str) -> Image.Image:
    if content_type.startswith("image/"):
        return Image.open(io.BytesIO(raw)).convert("RGB")

    if content_type.startswith("video/"):
        with tempfile.NamedTemporaryFile(suffix=".mp4") as h:
            h.write(raw)
            h.flush()
            cap = cv2.VideoCapture(h.name)
            ok, frame = cap.read()
            cap.release()
        if not ok:
            raise ValueError("Could not decode the uploaded source video")
        return Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))

    raise ValueError("Unsupported source content type")


def prepare_source(payload: dict) -> dict:
    session_id = str(payload["session_id"])
    encoded = payload.get("data_base64")
    if not encoded:
        raise ValueError("Source image/video data is required")

    raw = base64.b64decode(encoded)
    content_type = payload.get("content_type", "image/jpeg")
    image = decode_source_to_image(raw, content_type)

    with tempfile.NamedTemporaryFile(suffix=".jpg") as h:
        image.save(h, format="JPEG", quality=95)
        h.flush()
        with PIPELINE_LOCK:
            PIPELINE.init_vars()
            ok = PIPELINE.prepare_source(h.name, realtime=True)

    if not ok or not PIPELINE.src_imgs or not PIPELINE.src_infos:
        raise ValueError("FasterLivePortrait could not detect a face in the source")

    # The current live protocol uses one source portrait per session. Copy the
    # prepared arrays so later sessions cannot overwrite this session's source.
    SESSIONS[session_id] = {
        "src_img": np.array(PIPELINE.src_imgs[0], copy=True),
        "src_info": __import__("copy").deepcopy(PIPELINE.src_infos[0]),
        "prepared": True,
        "frame_count": 0,
    }
    return {"status": "ready", "worker_id": WORKER_ID, "session_id": session_id}


def create_session(payload: dict) -> dict:
    session_id = str(payload["session_id"])
    SESSIONS[session_id] = {"prepared": False, "frame_count": 0}
    return {"status": "ready", "worker_id": WORKER_ID, "session_id": session_id}


def render_frame(payload: dict) -> dict:
    session_id = str(payload["session_id"])
    session = SESSIONS.get(session_id)
    if not session or not session.get("prepared"):
        raise ValueError("Source is not prepared")

    images = payload.get("driving_images") or []
    if not images:
        raise ValueError("driving_images must contain at least one camera frame")

    encoded = images[0]
    raw = base64.b64decode(encoded)
    frame = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
    if frame is None:
        raise ValueError("Could not decode driving camera frame")

    with PIPELINE_LOCK:
        first = session["frame_count"] == 0
        _, out_crop, _, _ = PIPELINE.run(
            frame,
            session["src_img"],
            session["src_info"],
            first_frame=first,
            realtime=True,
        )
        session["frame_count"] += 1

    if out_crop is None:
        raise ValueError("FasterLivePortrait detected no face in the driving frame")

    out_rgb = np.asarray(out_crop, dtype=np.uint8)
    out_bgr = cv2.cvtColor(out_rgb, cv2.COLOR_RGB2BGR)
    ok, encoded_out = cv2.imencode(".jpg", out_bgr, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
    if not ok:
        raise RuntimeError("Failed to JPEG encode rendered frame")

    return {
        "status": "rendered",
        "worker_id": WORKER_ID,
        "mime_type": "image/jpeg",
        "image_base64": base64.b64encode(encoded_out.tobytes()).decode("ascii"),
        "renderer": "FasterLivePortrait",
        "frame_index": session["frame_count"],
    }


def process(payload: dict) -> dict:
    action = payload.get("action")
    if action == "CREATE_SESSION":
        return create_session(payload)
    if action == "PREPARE_SOURCE":
        return prepare_source(payload)
    if action == "RENDER_FRAME":
        return render_frame(payload)
    raise ValueError(f"Unknown action: {action}")


def run_connection() -> None:
    import json

    def on_open(ws):
        ws.send(json.dumps({
            "type": "register",
            "worker_id": WORKER_ID,
            "gpu": torch.cuda.get_device_name(0),
            "cuda": torch.version.cuda,
            "renderer": "FasterLivePortrait",
            "upstream_repo": "eneokonaniebiet/FasterLivePortrait1",
            "upstream_ref": "main",
        }))
        print("KEMZY_FASTERLIVE_GPU_WORKER_ONLINE", flush=True)

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
                    "renderer": "FasterLivePortrait",
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
    print("=== KÉMZY FASTERLIVEPORTRAIT OUTBOUND GPU WORKER ===", flush=True)
    print("CUDA:", torch.cuda.is_available(), flush=True)
    ensure_fasterlive()
    load_pipeline()
    run_connection()
