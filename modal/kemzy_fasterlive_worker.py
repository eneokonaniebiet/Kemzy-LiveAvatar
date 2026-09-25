from __future__ import annotations

import asyncio
import base64
import json
import os
import tempfile
import uuid
from pathlib import Path

import modal

APP_NAME = "kemzy-fasterliveportrait"
RENDERER_REPO = "https://github.com/eneokonaniebiet/FasterLivePortrait1.git"
RENDERER_REF = "main"
ROOT = Path("/opt/FasterLivePortrait1")
CHECKPOINTS = ROOT / "checkpoints"
VOLUME_PATH = "/opt/FasterLivePortrait1/checkpoints"
VOLUME_NAME = "kemzy-fasterliveportrait-models"
SECRET_NAME = "kemzy-worker-secret"

app = modal.App(APP_NAME)
model_volume = modal.Volume.from_name(VOLUME_NAME, create_if_missing=True)
worker_secret = modal.Secret.from_name(SECRET_NAME, required_keys=["KEMZY_WORKER_SECRET"])

# FasterLivePortrait's official Docker image already contains the CUDA/TensorRT/
# ONNX Runtime stack expected by the project. We add the user's fork on top.
image = (
    modal.Image.from_registry("shaoguo/faster_liveportrait:v3")
    .apt_install("git", "libgl1", "libglib2.0-0")
    .pip_install(
        "fastapi",
        "websockets",
        "huggingface_hub",
        "python-multipart",
    )
    .run_commands(
        f"rm -rf {ROOT}",
        f"git clone --depth 1 --branch {RENDERER_REF} {RENDERER_REPO} {ROOT}",
    )
)

with image.imports():
    import cv2
    import numpy as np
    import torch
    from fastapi import FastAPI, WebSocket, WebSocketDisconnect
    from fastapi.responses import JSONResponse
    from huggingface_hub import snapshot_download
    from omegaconf import OmegaConf

    import sys
    sys.path.insert(0, str(ROOT))
    from src.pipelines.faster_live_portrait_pipeline import FasterLivePortraitPipeline


REQUIRED_SENTINEL = CHECKPOINTS / "liveportrait_onnx" / "warping_spade.onnx"


def ensure_checkpoints() -> None:
    CHECKPOINTS.mkdir(parents=True, exist_ok=True)
    if REQUIRED_SENTINEL.exists():
        return

    print("Downloading FasterLivePortrait checkpoints into Modal Volume...", flush=True)
    snapshot_download(
        repo_id="warmshao/FasterLivePortrait",
        local_dir=str(CHECKPOINTS),
    )
    if not REQUIRED_SENTINEL.exists():
        raise RuntimeError(f"Missing required checkpoint: {REQUIRED_SENTINEL}")


def encode_jpeg_rgb(rgb: np.ndarray) -> str:
    bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    ok, encoded = cv2.imencode(".jpg", bgr, [int(cv2.IMWRITE_JPEG_QUALITY), 90])
    if not ok:
        raise RuntimeError("JPEG encoding failed")
    return base64.b64encode(encoded.tobytes()).decode("ascii")


@app.cls(
    image=image,
    gpu="T4",
    volumes={VOLUME_PATH: model_volume},
    secrets=[worker_secret],
    min_containers=1,
    max_containers=1,
    timeout=60 * 60,
)
class FasterLivePortraitWorker:
    @modal.enter()
    def load(self):
        os.chdir(ROOT)
        if not torch.cuda.is_available():
            raise RuntimeError("CUDA GPU is not available in the Modal container")

        ensure_checkpoints()

        cfg = OmegaConf.load(ROOT / "configs" / "onnx_infer.yaml")
        self.pipeline = FasterLivePortraitPipeline(cfg=cfg, is_animal=False)

        self.sessions: dict[str, dict] = {}
        self.lock = asyncio.Lock()
        print(
            "KEMZY_MODAL_READY "
            f"torch={torch.__version__} "
            f"cuda={torch.version.cuda} "
            f"gpu={torch.cuda.get_device_name(0)}",
            flush=True,
        )

        self.web_app = FastAPI(title="Kémzy FasterLivePortrait GPU Worker")

        @self.web_app.get("/health")
        async def health():
            return JSONResponse(
                {
                    "status": "ok",
                    "renderer": "FasterLivePortrait",
                    "backend": "FasterLivePortrait1",
                    "gpu": torch.cuda.get_device_name(0),
                    "workers": 1,
                }
            )

        @self.web_app.websocket("/gpu-bridge")
        async def gpu_bridge(ws: WebSocket):
            expected = os.getenv("KEMZY_WORKER_SECRET", "")
            supplied = ws.headers.get("x-kemzy-secret", "")
            if not expected or supplied != expected:
                await ws.close(code=1008)
                return

            await ws.accept()
            worker_id = f"modal-{uuid.uuid4().hex[:12]}"
            await ws.send_json(
                {
                    "type": "registered",
                    "worker_id": worker_id,
                    "status": "online",
                    "renderer": "FasterLivePortrait",
                    "backend": "FasterLivePortrait1",
                }
            )
            print(f"KEMZY_MODAL_WORKER_CONNECTED {worker_id}", flush=True)

            try:
                while True:
                    message = await ws.receive_json()
                    kind = message.get("type")

                    if kind == "heartbeat":
                        await ws.send_json({"type": "heartbeat_ack"})
                        continue

                    if kind != "job":
                        continue

                    job_id = str(message.get("jobId", ""))
                    action = str(message.get("action", ""))
                    data = message.get("data") or {}

                    try:
                        result = await self.handle_job(action, data)
                    except Exception as exc:
                        import traceback
                        traceback.print_exc()
                        result = {
                            "status": "error",
                            "error": f"{type(exc).__name__}: {exc}",
                        }

                    await ws.send_json(
                        {
                            "type": "result",
                            "jobId": job_id,
                            "result": {
                                **result,
                                "worker_id": worker_id,
                            },
                        }
                    )
            except WebSocketDisconnect:
                print(f"KEMZY_MODAL_WORKER_DISCONNECTED {worker_id}", flush=True)
            except Exception as exc:
                print(f"KEMZY_MODAL_WORKER_ERROR {worker_id}: {exc!r}", flush=True)

        self.web_app.add_api_route("/health", lambda: {"status": "ok"})

    async def handle_job(self, action: str, data: dict) -> dict:
        if action == "CREATE_SESSION":
            session_id = str(data["session_id"])
            async with self.lock:
                self.sessions[session_id] = {
                    "source_path": None,
                    "ready": False,
                    "first_frame": True,
                }
            return {"status": "ready"}

        if action == "PREPARE_SOURCE":
            session_id = str(data["session_id"])
            if session_id not in self.sessions:
                return {"status": "error", "error": "unknown_session"}

            raw = base64.b64decode(data["data_base64"])
            content_type = str(data.get("content_type") or "image/jpeg")
            suffix = ".mp4" if content_type.startswith("video/") else ".jpg"

            fd, path = tempfile.mkstemp(prefix=f"kemzy-{session_id}-", suffix=suffix)
            os.close(fd)
            Path(path).write_bytes(raw)

            ok = await asyncio.to_thread(
                self.pipeline.prepare_source,
                path,
                realtime=True,
            )
            if not ok:
                try:
                    Path(path).unlink(missing_ok=True)
                except Exception:
                    pass
                return {"status": "error", "error": "source_face_not_detected"}

            async with self.lock:
                self.sessions[session_id]["source_path"] = path
                self.sessions[session_id]["ready"] = True
                self.sessions[session_id]["first_frame"] = True

            return {"status": "ready"}

        if action == "RENDER_FRAME":
            session_id = str(data["session_id"])
            session = self.sessions.get(session_id)
            if not session or not session.get("ready"):
                return {"status": "error", "error": "source_not_ready"}

            driving_images = data.get("driving_images") or []
            if not driving_images:
                return {"status": "error", "error": "driving_images_missing"}

            frame_bytes = base64.b64decode(driving_images[0])
            arr = np.frombuffer(frame_bytes, dtype=np.uint8)
            frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if frame is None:
                return {"status": "error", "error": "invalid_driving_frame"}

            first = bool(session.get("first_frame", False))

            # The official pipeline computes expression/pose directly from the
            # camera frame. Motion scalars from the Android client remain useful
            # for UI/diagnostics but are not double-applied here.
            _, out_crop, _, _ = await asyncio.to_thread(
                self.pipeline.run,
                frame,
                self.pipeline.src_imgs[0],
                self.pipeline.src_infos[0],
                realtime=True,
                first_frame=first,
            )

            if out_crop is None:
                return {"status": "error", "error": "render_failed_no_face"}

            session["first_frame"] = False
            image_base64 = encode_jpeg_rgb(out_crop)
            return {
                "status": "rendered",
                "mime_type": "image/jpeg",
                "image_base64": image_base64,
            }

        return {"status": "error", "error": f"unknown_action:{action}"}

    @modal.asgi_app()
    def web(self):
        return self.web_app


@app.local_entrypoint()
def show_info():
    print("Kémzy FasterLivePortrait Modal app prepared.")
    print(f"App: {APP_NAME}")
    print(f"Volume: {VOLUME_NAME}")
    print("After deployment, the worker WebSocket endpoint is:")
    print("  <Modal app web URL>/gpu-bridge")
