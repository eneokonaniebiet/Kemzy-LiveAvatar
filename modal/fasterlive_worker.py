from __future__ import annotations

import base64
import copy
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import modal

APP_NAME = "kemzy-fasterliveportrait"
REPO_DIR = Path("/opt/FasterLivePortrait1")
MODEL_DIR = Path("/models/checkpoints")
HF_REPO = "warmshao/FasterLivePortrait"

model_volume = modal.Volume.from_name("kemzy-fasterliveportrait-models", create_if_missing=True)

image = (
    modal.Image.from_registry(
        "nvidia/cuda:12.8.1-cudnn-devel-ubuntu22.04",
        add_python="3.11",
    )
    .entrypoint([])
    .apt_install("git", "ffmpeg", "libgl1", "libglib2.0-0")
    .uv_pip_install(
        "torch==2.11.0",
        "numpy==1.26.4",
        "opencv-python",
        "pillow",
        "omegaconf",
        "onnx",
        "onnxruntime-gpu",
        "insightface",
        "mediapipe",
        "scikit-image",
        "torchgeometry",
        "munch",
        "tqdm",
        "huggingface_hub",
        "fastapi",
        "websockets==12.0",
    )
    .run_commands(
        f"git clone --depth 1 https://github.com/eneokonaniebiet/FasterLivePortrait1.git {REPO_DIR}"
    )
)

app = modal.App(APP_NAME, image=image)


def _ensure_models() -> Path:
    target = MODEL_DIR / "liveportrait_onnx" / "warping_spade.onnx"
    if target.exists():
        return MODEL_DIR

    from huggingface_hub import snapshot_download

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    print("Downloading FasterLivePortrait ONNX models to persistent Modal Volume...", flush=True)
    snapshot_download(
        repo_id=HF_REPO,
        local_dir=str(MODEL_DIR),
        allow_patterns=[
            "liveportrait_onnx/*",
            "liveportrait_animal_onnx/*",
        ],
    )
    model_volume.commit()
    return MODEL_DIR


def _prepare_repo() -> None:
    link = REPO_DIR / "checkpoints"
    if link.exists() or link.is_symlink():
        if link.is_symlink() and link.resolve() == MODEL_DIR.resolve():
            return
        if link.is_dir() and not link.is_symlink():
            shutil.rmtree(link)
        else:
            link.unlink()
    link.symlink_to(MODEL_DIR, target_is_directory=True)


@app.cls(
    gpu="A10G",
    volumes={"/models": model_volume},
    timeout=1800,
    max_containers=1,
    scaledown_window=300,
)
class FasterLivePortraitServer:
    @modal.enter()
    def load(self):
        _ensure_models()
        _prepare_repo()
        os.chdir(REPO_DIR)

        import torch
        from omegaconf import OmegaConf
        from src.pipelines.faster_live_portrait_pipeline import FasterLivePortraitPipeline

        if not torch.cuda.is_available():
            raise RuntimeError("CUDA GPU is required")

        cfg = OmegaConf.load(REPO_DIR / "configs/onnx_infer.yaml")
        cfg.infer_params.flag_pasteback = False
        cfg.infer_params.flag_do_crop = True
        cfg.infer_params.flag_stitching = True
        cfg.infer_params.flag_relative_motion = True
        cfg.infer_params.flag_crop_driving_video = False

        self.pipeline = FasterLivePortraitPipeline(cfg=cfg, is_animal=False)
        self.sessions: dict[str, dict] = {}
        print("KEMZY_MODAL_FASTERLIVE_READY", flush=True)
        print("GPU:", torch.cuda.get_device_name(0), flush=True)
        print("CUDA:", torch.version.cuda, flush=True)

    def _source_image(self, raw: bytes, content_type: str) -> str:
        suffix = ".jpg" if content_type.startswith("image/") else ".mp4"
        h = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
        try:
            h.write(raw)
            h.close()
            return h.name
        except Exception:
            h.close()
            raise

    def create_session(self, session_id: str) -> dict:
        self.sessions[session_id] = {"prepared": False, "frame_count": 0}
        return {"status": "ready", "session_id": session_id}

    def prepare_source(self, payload: dict) -> dict:
        session_id = str(payload["session_id"])
        raw = base64.b64decode(payload["data_base64"])
        content_type = payload.get("content_type", "image/jpeg")
        path = self._source_image(raw, content_type)
        try:
            self.pipeline.init_vars()
            ok = self.pipeline.prepare_source(path, realtime=True)
            if not ok or not self.pipeline.src_imgs or not self.pipeline.src_infos:
                raise ValueError("FasterLivePortrait could not detect a face in the source")

            self.sessions[session_id] = {
                "prepared": True,
                "src_img": copy.deepcopy(self.pipeline.src_imgs[0]),
                "src_info": copy.deepcopy(self.pipeline.src_infos[0]),
                "frame_count": 0,
            }
            return {"status": "ready", "session_id": session_id}
        finally:
            try:
                os.unlink(path)
            except OSError:
                pass

    def render_frame(self, payload: dict) -> dict:
        session_id = str(payload["session_id"])
        session = self.sessions.get(session_id)
        if not session or not session["prepared"]:
            raise ValueError("Source is not prepared")

        images = payload.get("driving_images") or []
        if not images:
            raise ValueError("driving_images must contain at least one camera frame")

        raw = base64.b64decode(images[0])
        import cv2
        import numpy as np

        frame = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
        if frame is None:
            raise ValueError("Could not decode driving camera frame")

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
            raise ValueError("FasterLivePortrait returned no rendered frame")

        out = np.asarray(out_crop, dtype=np.uint8)
        out = cv2.cvtColor(out, cv2.COLOR_RGB2BGR)
        ok, jpg = cv2.imencode(".jpg", out, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
        if not ok:
            raise RuntimeError("Failed to encode rendered frame")

        return {
            "status": "rendered",
            "mime_type": "image/jpeg",
            "image_base64": base64.b64encode(jpg.tobytes()).decode("ascii"),
            "renderer": "FasterLivePortrait",
            "frame_index": session["frame_count"],
        }

    @modal.asgi_app()
    def web(self):
        from fastapi import FastAPI, WebSocket, WebSocketDisconnect

        web_app = FastAPI()

        @web_app.get("/health")
        async def health():
            return {
                "status": "ok",
                "renderer": "FasterLivePortrait",
                "backend": "FasterLivePortrait1",
                "gpu": "A10G",
            }

        @web_app.websocket("/ws")
        async def websocket_handler(ws: WebSocket):
            await ws.accept()
            try:
                hello = await ws.receive_json()
                if hello.get("type") != "register":
                    await ws.close(code=1008)
                    return

                worker_id = hello.get("worker_id", "modal-gpu")
                await ws.send_json({
                    "type": "registered",
                    "worker_id": worker_id,
                    "status": "online",
                    "renderer": "FasterLivePortrait",
                })

                while True:
                    msg = await ws.receive_json()
                    if msg.get("type") == "heartbeat":
                        await ws.send_json({"type": "heartbeat_ack"})
                        continue
                    if msg.get("type") != "job":
                        continue

                    try:
                        action = msg.get("action")
                        data = msg.get("data", {})
                        if action == "CREATE_SESSION":
                            result = self.create_session(str(data["session_id"]))
                        elif action == "PREPARE_SOURCE":
                            result = self.prepare_source(data)
                        elif action == "RENDER_FRAME":
                            result = self.render_frame(data)
                        else:
                            raise ValueError(f"Unknown action: {action}")
                    except Exception as exc:
                        result = {
                            "status": "failed",
                            "error": f"{type(exc).__name__}: {exc}",
                            "renderer": "FasterLivePortrait",
                        }

                    await ws.send_json({
                        "type": "result",
                        "jobId": msg.get("jobId"),
                        "result": result,
                    })
            except WebSocketDisconnect:
                return

        return web_app


if __name__ == "__main__":
    print("Deploy with: modal deploy modal/fasterlive_worker.py")
