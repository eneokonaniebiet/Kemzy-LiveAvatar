import os
import uuid
from typing import Any

import httpx
from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel, Field

app = FastAPI(title="Kémzy àvátâr API", version="0.1.0")

GPU_RENDERER_URL = os.getenv("GPU_RENDERER_URL", "").rstrip("/")
GPU_RENDERER_TOKEN = os.getenv("GPU_RENDERER_TOKEN", "")


class SessionCreate(BaseModel):
    source_type: str = Field(pattern="^(image|video)$")


class MotionFrame(BaseModel):
    session_id: str
    timestamp_ms: int = Field(ge=0)
    pose: list[float] = Field(default_factory=list)
    expression: list[float] = Field(default_factory=list)
    landmarks: list[float] = Field(default_factory=list)


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "ok", "service": "kemzy-api", "version": app.version}


@app.get("/ready")
def ready() -> dict[str, Any]:
    if not GPU_RENDERER_URL:
        return {"status": "degraded", "renderer": "not_configured"}
    return {"status": "ready", "renderer": GPU_RENDERER_URL}


@app.post("/v1/sessions")
def create_session(request: SessionCreate) -> dict[str, Any]:
    return {
        "session_id": str(uuid.uuid4()),
        "source_type": request.source_type,
        "renderer": "gpu",
        "status": "created",
    }


@app.post("/v1/sessions/{session_id}/source")
async def upload_source(session_id: str, file: UploadFile = File(...)) -> dict[str, Any]:
    if file.content_type not in {"image/jpeg", "image/png", "image/webp", "video/mp4", "video/webm"}:
        raise HTTPException(status_code=415, detail="Unsupported source media type")
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="Empty source file")
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Source file exceeds 25 MB")
    if not GPU_RENDERER_URL:
        return {"session_id": session_id, "status": "accepted", "renderer": "pending"}
    headers = {"Authorization": f"Bearer {GPU_RENDERER_TOKEN}"} if GPU_RENDERER_TOKEN else {}
    async with httpx.AsyncClient(timeout=60) as client:
        response = await client.post(
            f"{GPU_RENDERER_URL}/v1/sessions/{session_id}/source",
            content=data,
            headers={**headers, "Content-Type": file.content_type},
        )
    if response.is_error:
        raise HTTPException(status_code=502, detail="GPU renderer rejected source")
    return response.json()


@app.post("/v1/render/frame")
async def render_frame(frame: MotionFrame) -> dict[str, Any]:
    if not GPU_RENDERER_URL:
        raise HTTPException(status_code=503, detail="GPU renderer is not configured")
    headers = {"Authorization": f"Bearer {GPU_RENDERER_TOKEN}"} if GPU_RENDERER_TOKEN else {}
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(
            f"{GPU_RENDERER_URL}/v1/render/frame",
            json=frame.model_dump(),
            headers=headers,
        )
    if response.is_error:
        raise HTTPException(status_code=502, detail="GPU renderer failed")
    return response.json()
