from __future__ import annotations

import base64
import copy
import io
import json
import os
import sys
import tempfile
import threading
import time
import uuid
from pathlib import Path

import modal

APP_NAME = "kemzy-liveavatar-gpu"
VOLUME_NAME = "kemzy-fasterlive-checkpoints"
WORKER_SECRET_NAME = "kemzy-gpu-worker"
ROOT = Path("/opt/FasterLivePortrait1")
DATA = Path("/data")
CHECKPOINTS = DATA / "checkpoints"

volume = modal.Volume.from_name(VOLUME_NAME, create_if_missing=True)

image = (
    modal.Image.from_registry(
        "nvidia/cuda:11.8.0-cudnn8-runtime-ubuntu22.04",
        add_python="3.11",
    )
    .apt_install(
        "git",
        "ffmpeg",
        "libgl1",
        "libglib2.0-0",
        "libsm6",
        "libxext6",
        "libxrender1",
        "libgomp1",
        "ca-certificates",
    )
    .pip_install(
        "torch==2.1.2",
        "torchvision==0.16.2",
        index_url="https://download.pytorch.org/whl/cu118",
    )
    .pip_install(
        "ffmpeg-python",
        "omegaconf",
        "onnx",
        "numpy<2",
        "opencv-python-headless",
        "scikit-image",
        "insightface",
        "huggingface_hub",
        "mediapipe",
        "torchgeometry",
        "soundfile",
        "munch",
        "websocket-client>=1.8,<2",
        "onnxruntime-gpu>=1.20,<2",
        "fastapi",
    )
    .run_commands(
        "git clone --depth 1 https://github.com/eneokonaniebiet/FasterLivePortrait1.git /opt/FasterLivePortrait1"
    )
)

app = modal.App(APP_NAME)


@app.cls(
    image=image,
    gpu="A10G",
    volumes={"/data": volume},
    secrets=[modal.Secret.from_name(WORKER_SECRET_NAME)],
    timeout=1200,
    startup_timeout=1200,
    scaledown_window=300,
)
@modal.concurrent(max_inputs=1)
class KemzyGPUWorker:
    def __enter__(self):
        self.worker_id = f"modal-{uuid.uuid4().hex[:12]}"
        self.sessions: dict[str, dict] = {}
        self.pipeline = None
        self.pipeline_lock = threading.Lock()
        self._prepare_runtime()

    def _prepare_runtime(self):
        os.chdir(ROOT)
        sys.path.insert(0, str(ROOT))

        marker = CHECKPOINTS / "liveportrait_onnx" / "warping_spade.onnx"
        if not marker.exists():
            from huggingface_hub import snapshot_download

            print("DOWNLOADING_FASTERLIVE_CHECKPOINTS", flush=True)
            snapshot_download(
                repo_id="warmshao/FasterLivePortrait",
                local_dir=str(CHECKPOINTS),
                local_dir_use_symlinks=False,
            )
            volume.commit()

        link = ROOT / "checkpoints"
        if link.exists() or link.is_symlink():
            if link.is_symlink() and link.resolve() == CHECKPOINTS.resolve():
                pass
            elif link.is_dir():
                import shutil
                shutil.rmtree(link)
            else:
                link.unlink()
        if not link.exists():
            link.symlink_to(CHECKPOINTS, target_is_directory=True)

        import torch

        if not torch.cuda.is_available():
            raise RuntimeError("CUDA GPU is required")
        torch.cuda.set_device(0)

        from omegaconf import OmegaConf
        from src.pipelines.faster_live_portrait_pipeline import FasterLivePortraitPipeline

        cfg = OmegaConf.load(ROOT / "configs/onnx_infer.yaml")
        cfg.infer_params.flag_pasteback = False
        cfg.infer_params.flag_do_crop = True
        cfg.infer_params.flag_stitching = True
        cfg.infer_params.flag_relative_motion = True
        cfg.infer_params.flag_crop_driving_video = False

        self.pipeline = FasterLivePortraitPipeline(cfg=cfg, is_animal=False)
        print("KEMZY_MODAL_PIPELINE_READY", flush=True)
        print("GPU:", torch.cuda.get_device_name(0), flush=True)
        print("CUDA:", torch.version.cuda, flush=True)

    def _decode_source(self, raw: bytes, content_type: str):
        from PIL import Image
        import cv2

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
                raise ValueError("Could not decode source video")
            return Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        raise ValueError("Unsupported source content type")

    def process(self, payload: dict) -> dict:
        import cv2
        import numpy as np

        action = payload.get("action")
        session_id = str(payload.get("session_id", ""))
        if not session_id:
            raise ValueError("session_id is required")

        if action == "CREATE_SESSION":
            self.sessions[session_id] = {"prepared": False, "frame_count": 0}
            return {"status": "ready", "worker_id": self.worker_id, "session_id": session_id}

        if action == "PREPARE_SOURCE":
            encoded = payload.get("data_base64")
            if not encoded:
                raise ValueError("Source image/video data is required")
            raw = base64.b64decode(encoded)
            image = self._decode_source(raw, payload.get("content_type", "image/jpeg"))
            with tempfile.NamedTemporaryFile(suffix=".jpg") as h:
                image.save(h, format="JPEG", quality=95)
                h.flush()
                with self.pipeline_lock:
                    self.pipeline.init_vars()
                    ok = self.pipeline.prepare_source(h.name, realtime=True)
                    if not ok or not self.pipeline.src_imgs or not self.pipeline.src_infos:
                        raise ValueError("FasterLivePortrait could not detect a face in the source")
                    self.sessions[session_id] = {
                        "src_img": np.array(self.pipeline.src_imgs[0], copy=True),
                        "src_info": copy.deepcopy(self.pipeline.src_infos[0]),
                        "prepared": True,
                        "frame_count": 0,
                    }
            return {"status": "ready", "worker_id": self.worker_id, "session_id": session_id}

        if action == "RENDER_FRAME":
            session = self.sessions.get(session_id)
            if not session or not session.get("prepared"):
                raise ValueError("Source is not prepared")
            images = payload.get("driving_images") or []
            if not images:
                raise ValueError("driving_images must contain at least one camera frame")
            raw = base64.b64decode(images[0])
            frame = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
            if frame is None:
                raise ValueError("Could not decode driving frame")
            with self.pipeline_lock:
                first = session["frame_count"] == 0
                _, out_crop, _, _ = self.pipeline.run(
                    frame,
                    session["src_img"],
                    session["src_info"],
                    first_frame=first,
                    realtime=True,
                )
                session["frame_count"] += 1
            if out_crop is None:
                raise ValueError("FasterLivePortrait detected no face in driving frame")
            out_bgr = cv2.cvtColor(np.asarray(out_crop, dtype=np.uint8), cv2.COLOR_RGB2BGR)
            ok, encoded_out = cv2.imencode(
                ".jpg", out_bgr, [int(cv2.IMWRITE_JPEG_QUALITY), 85]
            )
            if not ok:
                raise RuntimeError("Failed to JPEG encode rendered frame")
            return {
                "status": "rendered",
                "worker_id": self.worker_id,
                "mime_type": "image/jpeg",
                "image_base64": base64.b64encode(encoded_out.tobytes()).decode("ascii"),
                "renderer": "FasterLivePortrait",
                "frame_index": session["frame_count"],
            }

        raise ValueError(f"Unknown action: {action}")

    @modal.asgi_app()
    def web(self):
        from fastapi import FastAPI, WebSocket, WebSocketDisconnect

        api = FastAPI()
        expected_secret = os.environ.get("GPU_WORKER_SECRET", "")

        @api.get("/health")
        async def health():
            return {"status": "ok", "worker": self.worker_id, "renderer": "FasterLivePortrait"}

        @api.websocket("/gpu-bridge")
        async def gpu_bridge(ws: WebSocket):
            supplied = ws.headers.get("x-worker-auth", "")
            if expected_secret and supplied != expected_secret:
                await ws.close(code=1008)
                return

            await ws.accept()
            print("KEMZY_RENDER_GATEWAY_CONNECTED", flush=True)
            try:
                while True:
                    raw = await ws.receive_text()
                    msg = json.loads(raw)
                    kind = msg.get("type")

                    if kind == "register":
                        await ws.send_text(json.dumps({
                            "type": "registered",
                            "worker_id": self.worker_id,
                        }))
                    elif kind == "heartbeat":
                        await ws.send_text(json.dumps({"type": "heartbeat_ack"}))
                    elif kind == "job":
                        try:
                            result = self.process(msg.get("data", {}))
                        except Exception as exc:
                            print("JOB_ERROR:", repr(exc), flush=True)
                            result = {
                                "status": "failed",
                                "error": f"{type(exc).__name__}: {exc}",
                                "renderer": "FasterLivePortrait",
                            }
                        await ws.send_text(json.dumps({
                            "type": "result",
                            "jobId": msg.get("jobId"),
                            "result": result,
                        }))
            except WebSocketDisconnect:
                print("KEMZY_RENDER_GATEWAY_DISCONNECTED", flush=True)

        return api
