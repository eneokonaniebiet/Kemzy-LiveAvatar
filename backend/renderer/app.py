import base64
import io
import os
import time
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel, Field
from PIL import Image

from .liveportrait_adapter import LivePortraitAdapter

app = FastAPI(title="Kémzy Neural Renderer", version="0.2.0")
MODEL_DIR = os.getenv("MODEL_DIR", "/models")
MODEL_BACKEND = "liveportrait-pytorch"
adapter = LivePortraitAdapter()

_SESSION_SOURCES: dict[str, str] = {}


class MotionFrame(BaseModel):
    session_id: str
    timestamp_ms: int = Field(ge=0)
    pose: list[float] = Field(default_factory=list)
    expression: list[float] = Field(default_factory=list)
    landmarks: list[float] = Field(default_factory=list)


def _png_b64(image: Image.Image) -> str:
    buf = io.BytesIO()
    image.save(buf, format="PNG", optimize=False)
    return base64.b64encode(buf.getvalue()).decode("ascii")


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ready" if adapter.ready else "degraded",
        "service": "kemzy-neural-renderer",
        "backend": MODEL_BACKEND,
        "liveportrait_commit": os.getenv("LIVEPORTRAIT_COMMIT", "9b294b3d0536135442ea73cb01e6cb3ca7029dd3"),
        "error": adapter.error,
    }


@app.get("/ready")
def ready() -> dict[str, Any]:
    model_files = []
    if os.path.isdir(MODEL_DIR):
        model_files = [name for name in os.listdir(MODEL_DIR) if not name.startswith(".")]
    return {
        "status": "ready" if adapter.ready else "degraded",
        "backend": MODEL_BACKEND,
        "model_dir": MODEL_DIR,
        "model_files": model_files,
        "error": adapter.error,
    }


@app.post("/v1/sessions/{session_id}/source")
async def source(session_id: str, file: UploadFile = File(...)) -> dict[str, Any]:
    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(status_code=415, detail="Live mode currently requires an image source")
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="Empty source file")
    try:
        image = Image.open(io.BytesIO(data)).convert("RGB")
        handle = adapter.prepare_source(image)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"LivePortrait source preparation failed: {exc}") from exc
    _SESSION_SOURCES[session_id] = handle
    return {
        "session_id": session_id,
        "status": "source_ready",
        "source_handle": handle,
        "backend": MODEL_BACKEND,
    }


@app.post("/v1/render/frame")
def render_frame(frame: MotionFrame) -> dict[str, Any]:
    handle = _SESSION_SOURCES.get(frame.session_id)
    if not handle:
        raise HTTPException(status_code=409, detail="No prepared source for this session")
    started = time.perf_counter()
    try:
        image = adapter.render(handle, frame.pose, frame.expression)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"LivePortrait render failed: {exc}") from exc
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return {
        "session_id": frame.session_id,
        "timestamp_ms": frame.timestamp_ms,
        "render_ms": round(elapsed_ms, 2),
        "width": image.width,
        "height": image.height,
        "mime_type": "image/png",
        "frame_base64": _png_b64(image),
    }
