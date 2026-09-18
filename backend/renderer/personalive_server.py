from __future__ import annotations

import argparse
import asyncio
import base64
import io
import os
import threading
import time
import uuid

import torch
from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from PIL import Image

from webcam.config import Args
from webcam.util import bytes_to_tensor, pil_to_frame
from webcam.vid2vid import Pipeline

app = FastAPI(title="Kémzy àvátâr — PersonaLive", version="1.1.0")
SESSIONS = {}
LOCK = threading.Lock()
APP_ARGS = None

PERSONALIVE_DEFAULT_COMMIT = "abdd112e01dcf7d89122c2e5efa29fcff0669740"
REQUIRED_WEIGHTS = (
    "personalive/denoising_unet.pth",
    "personalive/motion_encoder.pth",
    "personalive/motion_extractor.pth",
    "personalive/pose_guider.pth",
    "personalive/reference_unet.pth",
    "personalive/temporal_module.pth",
    "sd-vae-ft-mse/diffusion_pytorch_model.bin",
    "sd-vae-ft-mse/config.json",
    "sd-image-variations-diffusers/image_encoder/pytorch_model.bin",
    "sd-image-variations-diffusers/image_encoder/config.json",
    "sd-image-variations-diffusers/unet/diffusion_pytorch_model.bin",
    "sd-image-variations-diffusers/unet/pytorch_model.bin",
    "sd-image-variations-diffusers/unet/config.json",
    "sd-image-variations-diffusers/model_index.json",
)


def build_args() -> Args:
    return Args(
        host=os.getenv("HOST", "0.0.0.0"),
        port=int(os.getenv("PORT", "7860")),
        reload=False,
        mode=os.getenv("MODE", "default"),
        max_queue_size=int(os.getenv("MAX_QUEUE_SIZE", "4")),
        timeout=float(os.getenv("TIMEOUT", "0")),
        safety_checker=os.getenv("SAFETY_CHECKER", "False") == "True",
        taesd=os.getenv("USE_TAESD", "True") == "True",
        ssl_certfile=None,
        ssl_keyfile=None,
        debug=False,
        acceleration=os.getenv("ACCELERATION", "xformers"),
        engine_dir=os.getenv("ENGINE_DIR", "engines"),
        config_path=os.getenv(
            "PERSONALIVE_CONFIG",
            "./configs/prompts/personalive_online.yaml",
        ),
    )


def weights_status() -> dict:
    root = os.getenv("MODEL_DIR", "/models") + "/pretrained_weights"
    missing = [path for path in REQUIRED_WEIGHTS if not os.path.isfile(os.path.join(root, path))]
    return {"root": root, "ready": not missing, "missing": missing}


class Session:
    def __init__(self):
        if not torch.cuda.is_available():
            raise RuntimeError("GPU_REQUIRED: PersonaLive requires a CUDA GPU")
        self.pipeline = Pipeline(APP_ARGS, torch.device("cuda:0"))
        self.source_ready = False
        self.closed = False

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.pipeline.close()
        except Exception:
            pass


@app.get("/health")
def health():
    cuda = torch.cuda.is_available()
    weights = weights_status()
    return {
        "status": "ok" if cuda and weights["ready"] else "degraded",
        "renderer": "PersonaLive",
        "personalive_commit": os.getenv("PERSONALIVE_COMMIT", PERSONALIVE_DEFAULT_COMMIT),
        "cuda": cuda,
        "cuda_device": torch.cuda.get_device_name(0) if cuda else None,
        "acceleration": APP_ARGS.acceleration if APP_ARGS else os.getenv("ACCELERATION", "xformers"),
        "weights_ready": weights["ready"],
        "weights_missing": weights["missing"],
    }


@app.get("/ready")
def ready():
    state = health()
    state["ready_for_sessions"] = bool(state["cuda"] and state["weights_ready"])
    return state


@app.get("/v1/diagnostics/gpu")
def gpu_diagnostic():
    if not torch.cuda.is_available():
        raise HTTPException(
            status_code=503,
            detail={
                "code": "GPU_REQUIRED",
                "message": "No CUDA GPU is available. PersonaLive neural rendering is not being claimed as ready.",
            },
        )
    weights = weights_status()
    if not weights["ready"]:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "WEIGHTS_MISSING",
                "missing": weights["missing"],
            },
        )
    session = None
    try:
        session = Session()
        return {
            "status": "initialized",
            "renderer": "PersonaLive",
            "cuda": True,
            "device": torch.cuda.get_device_name(0),
            "pipeline_initialized": True,
            "note": "This diagnostic proves CUDA + model pipeline initialization. A live frame test still requires a real reference image and four driving frames.",
        }
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail={"code": "PIPELINE_INIT_FAILED", "message": str(exc)},
        ) from exc
    finally:
        if session is not None:
            session.close()


@app.post("/v1/diagnostics/render")
async def render_diagnostic(
    reference: UploadFile = File(...),
    frame1: UploadFile = File(...),
    frame2: UploadFile = File(...),
    frame3: UploadFile = File(...),
    frame4: UploadFile = File(...),
):
    """
    Deterministic CUDA smoke test for the real PersonaLive render path.

    This endpoint intentionally requires a reference image plus four driving
    frames because upstream PersonaLive processes driving input in chunks of
    four. It returns one generated JPEG only after process_input() has
    produced an output frame. A successful response is therefore stronger
    evidence than /v1/diagnostics/gpu, which only proves pipeline startup.
    """
    if not torch.cuda.is_available():
        raise HTTPException(
            status_code=503,
            detail={"code": "GPU_REQUIRED", "message": "CUDA is required for neural rendering."},
        )
    weights = weights_status()
    if not weights["ready"]:
        raise HTTPException(
            status_code=503,
            detail={"code": "WEIGHTS_MISSING", "missing": weights["missing"]},
        )

    uploads = [reference, frame1, frame2, frame3, frame4]
    payloads = []
    for upload in uploads:
        data = await upload.read()
        if not data or len(data) > 25 * 1024 * 1024:
            raise HTTPException(status_code=400, detail="Invalid diagnostic image size")
        payloads.append(data)

    session = None
    started = time.perf_counter()
    try:
        session = Session()
        reference_image = Image.open(io.BytesIO(payloads[0])).convert("RGB")
        session.pipeline.fuse_reference(reference_image)
        session.source_ready = True

        for data in payloads[1:]:
            params = Pipeline.InputParams()
            params.image = bytes_to_tensor(data)
            session.pipeline.accept_new_params(params)

        deadline = time.monotonic() + float(os.getenv("DIAGNOSTIC_RENDER_TIMEOUT", "120"))
        generated = []
        while time.monotonic() < deadline:
            generated = session.pipeline.produce_outputs()
            if generated:
                break
            await asyncio.sleep(0.01)

        if not generated:
            raise HTTPException(
                status_code=504,
                detail={
                    "code": "RENDER_TIMEOUT",
                    "message": "PersonaLive accepted the four driving frames but produced no output before the diagnostic timeout.",
                    "timeout_seconds": float(os.getenv("DIAGNOSTIC_RENDER_TIMEOUT", "120")),
                },
            )

        jpeg = pil_to_frame(generated[0])
        elapsed = time.perf_counter() - started
        return {
            "status": "rendered",
            "renderer": "PersonaLive",
            "cuda": True,
            "device": torch.cuda.get_device_name(0),
            "pipeline_initialized": True,
            "source_fused": True,
            "driving_frames_submitted": 4,
            "generated_frames": len(generated),
            "first_frame_jpeg_base64": base64.b64encode(jpeg).decode("ascii"),
            "render_seconds": round(elapsed, 3),
        }
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail={"code": "RENDER_FAILED", "message": str(exc)},
        ) from exc
    finally:
        if session is not None:
            session.close()


@app.post("/v1/sessions")
def create_session():
    session_id = str(uuid.uuid4())
    try:
        session = Session()
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail=f"PersonaLive initialization failed: {exc}",
        ) from exc
    with LOCK:
        SESSIONS[session_id] = session
    return {
        "session_id": session_id,
        "renderer": "PersonaLive",
        "status": "created",
    }


@app.post("/v1/sessions/{session_id}/source")
async def upload_source(session_id: str, file: UploadFile = File(...)):
    session = SESSIONS.get(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Unknown session")
    data = await file.read()
    if not data or len(data) > 100 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="Invalid source size")
    try:
        image = Image.open(io.BytesIO(data)).convert("RGB")
        session.pipeline.fuse_reference(image)
        session.source_ready = True
    except Exception as exc:
        raise HTTPException(
            status_code=422,
            detail=f"PersonaLive reference preparation failed: {exc}",
        ) from exc
    return {
        "session_id": session_id,
        "status": "source_ready",
        "renderer": "PersonaLive",
    }


@app.post("/v1/sessions/{session_id}/reset")
def reset_session(session_id: str):
    session = SESSIONS.get(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Unknown session")
    session.pipeline.reset()
    session.source_ready = False
    return {"status": "reset"}


async def _output_pump(session: Session, websocket: WebSocket):
    while True:
        frames = session.pipeline.produce_outputs()
        for frame in frames:
            await websocket.send_bytes(pil_to_frame(frame))
        await asyncio.sleep(0.01)


@app.websocket("/v1/stream/{session_id}")
async def stream(session_id: str, websocket: WebSocket):
    session = SESSIONS.get(session_id)
    if session is None or not session.source_ready:
        await websocket.close(code=1008)
        return
    await websocket.accept()
    pump = asyncio.create_task(_output_pump(session, websocket))
    try:
        while True:
            message = await websocket.receive()
            data = message.get("bytes")
            if not data:
                continue
            params = Pipeline.InputParams()
            params.image = bytes_to_tensor(data)
            session.pipeline.accept_new_params(params)
    except WebSocketDisconnect:
        return
    except Exception as exc:
        try:
            await websocket.send_json({"status": "error", "message": str(exc)})
        except Exception:
            pass
    finally:
        pump.cancel()
        try:
            await pump
        except asyncio.CancelledError:
            pass


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.getenv("HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.getenv("PORT", "7860")))
    parser.add_argument(
        "--acceleration",
        choices=["none", "xformers", "tensorrt"],
        default=os.getenv("ACCELERATION", "xformers"),
    )
    parser.add_argument("--engine-dir", default=os.getenv("ENGINE_DIR", "engines"))
    parser.add_argument(
        "--config_path",
        default=os.getenv("PERSONALIVE_CONFIG", "./configs/prompts/personalive_online.yaml"),
    )
    return parser.parse_args()


if __name__ == "__main__":
    parsed = parse_args()
    APP_ARGS = build_args()
    APP_ARGS = APP_ARGS._replace(
        host=parsed.host,
        port=parsed.port,
        acceleration=parsed.acceleration,
        engine_dir=parsed.engine_dir,
        config_path=parsed.config_path,
    )
    import uvicorn
    uvicorn.run(app, host=parsed.host, port=parsed.port)
else:
    APP_ARGS = build_args()
