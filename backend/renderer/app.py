import base64
import io
import os
import time
import uuid
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel, Field
from PIL import Image

app = FastAPI(title="Kémzy Neural Renderer", version="0.1.0")
MODEL_DIR = os.getenv("MODEL_DIR", "/models")
MODEL_BACKEND = os.getenv("MODEL_BACKEND", "unconfigured")

class MotionFrame(BaseModel):
    session_id: str
    timestamp_ms: int = Field(ge=0)
    pose: list[float] = Field(default_factory=list)
    expression: list[float] = Field(default_factory=list)
    landmarks: list[float] = Field(default_factory=list)

@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "ok", "service": "kemzy-neural-renderer", "version": app.version}

@app.get("/ready")
def ready() -> dict[str, Any]:
    model_files = []
    if os.path.isdir(MODEL_DIR):
        model_files = [name for name in os.listdir(MODEL_DIR) if not name.startswith(".")]
    configured = MODEL_BACKEND != "unconfigured" and bool(model_files)
    return {
        "status": "ready" if configured else "degraded",
        "backend": MODEL_BACKEND,
        "model_dir": MODEL_DIR,
        "model_files": model_files,
    }

@app.post("/v1/sessions/{session_id}/source")
async def source(session_id: str, file: UploadFile = File(...)) -> dict[str, Any]:
    if not file.content_type or not file.content_type.startswith(("image/", "video/")):
        raise HTTPException(status_code=415, detail="Unsupported source media type")
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="Empty source file")
    if file.content_type.startswith("image/"):
        try:
            image = Image.open(io.BytesIO(data))
            image.verify()
        except Exception as exc:
            raise HTTPException(status_code=400, detail="Invalid image source") from exc
    return {"session_id": session_id, "status": "source_received", "bytes": len(data)}

@app.post("/v1/render/frame")
def render_frame(frame: MotionFrame) -> dict[str, Any]:
    if MODEL_BACKEND == "unconfigured":
        raise HTTPException(status_code=503, detail="Neural renderer backend is not configured")
    started = time.perf_counter()
    # The production renderer is injected here. This service deliberately does
    # not return a fake frame: readiness requires a real model adapter.
    raise HTTPException(
        status_code=501,
        detail="Production neural renderer adapter is not installed",
    )
