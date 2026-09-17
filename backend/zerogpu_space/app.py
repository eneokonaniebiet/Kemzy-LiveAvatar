from __future__ import annotations

import base64
import io
import os
import uuid
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field, ValidationError
from PIL import Image

from liveportrait_adapter import LivePortraitAdapter

app = FastAPI(title="Kémzy Neural Live Avatar", version="1.0.0")
adapter = LivePortraitAdapter()
_SESSION_HANDLES: dict[str, str] = {}

if os.getenv("KEMZY_SKIP_MODEL_LOAD", "0") != "1":
    try:
        adapter.load()
    except Exception:
        pass


class SessionCreate(BaseModel):
    source_type: str = Field(pattern="^(image|video)$")


class MotionFrame(BaseModel):
    timestamp_ms: int = Field(ge=0)
    pose: list[float]
    expression: list[float]
    landmarks: list[float] = Field(default_factory=list)
    eye_ratio: float | None = Field(default=None, ge=0.0, le=1.0)
    lip_ratio: float | None = Field(default=None, ge=0.0, le=1.0)

    def validate_driver(self) -> None:
        if len(self.pose) != 3:
            raise ValueError("pose must contain exactly 3 values")
        if len(self.expression) != 63:
            raise ValueError("expression must contain exactly 63 values")
        if len(self.landmarks) % 3 != 0:
            raise ValueError("landmarks must contain x,y,z triplets")


def _encode_image(image: Image.Image) -> str:
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=True)
    return base64.b64encode(output.getvalue()).decode("ascii")


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ready" if adapter.ready else "degraded",
        "service": "kemzy-neural-renderer",
        "backend": "liveportrait-pytorch",
        "error": adapter.error,
    }


@app.get("/ready")
def ready() -> dict[str, Any]:
    return health()


@app.post("/v1/sessions")
def create_session(request: SessionCreate) -> dict[str, Any]:
    session_id = str(uuid.uuid4())
    return {"session_id": session_id, "source_type": request.source_type, "status": "created"}


@app.post("/v1/sessions/{session_id}/source")
async def upload_source(session_id: str, file: UploadFile = File(...)) -> dict[str, Any]:
    if file.content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(status_code=415, detail="Live neural streaming currently requires an image source")
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="Empty source file")
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Source file exceeds 25 MB")
    try:
        image = Image.open(io.BytesIO(data)).convert("RGB")
        handle = adapter.prepare_source(image)
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"Source preparation failed: {exc}") from exc
    _SESSION_HANDLES[session_id] = handle
    return {"session_id": session_id, "status": "source_ready", "source_handle": handle}


async def _render(session_id: str, frame: MotionFrame) -> dict[str, Any]:
    frame.validate_driver()
    handle = _SESSION_HANDLES.get(session_id)
    if not handle:
        raise RuntimeError("Source has not been prepared for this session")
    image = adapter.render(
        handle,
        frame.pose,
        frame.expression,
        eye_ratio=frame.eye_ratio,
        lip_ratio=frame.lip_ratio,
    )
    return {
        "status": "rendered",
        "mime_type": "image/png",
        "image_base64": _encode_image(image),
    }


@app.websocket("/v1/stream/{session_id}")
async def stream_motion(websocket: WebSocket, session_id: str) -> None:
    await websocket.accept()
    if session_id not in _SESSION_HANDLES:
        await websocket.send_json({"type": "error", "code": "source_not_ready", "message": "Source has not been prepared for this session"})
        await websocket.close(code=1008)
        return
    if not adapter.ready:
        await websocket.send_json({"type": "error", "code": "renderer_unavailable", "message": adapter.error or "Neural renderer is not ready"})
        await websocket.close(code=1013)
        return

    try:
        while True:
            payload = await websocket.receive_json()
            try:
                frame = MotionFrame.model_validate(payload)
                frame.validate_driver()
            except (ValidationError, ValueError) as exc:
                await websocket.send_json({"type": "error", "code": "invalid_motion", "message": str(exc)})
                continue

            try:
                result = await _render(session_id, frame)
            except Exception as exc:
                await websocket.send_json({"type": "error", "code": "render_failed", "message": str(exc)})
                continue

            await websocket.send_json({
                "type": "frame",
                "session_id": session_id,
                "timestamp_ms": frame.timestamp_ms,
                "mime_type": result["mime_type"],
                "frame_base64": result["image_base64"],
            })
    except WebSocketDisconnect:
        return


@app.post("/v1/render/frame")
async def render_frame(session_id: str, frame: MotionFrame) -> dict[str, Any]:
    try:
        result = await _render(session_id, frame)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {"session_id": session_id, "timestamp_ms": frame.timestamp_ms, **result}


@app.get("/")
def root() -> dict[str, str]:
    return {"service": "Kémzy àvátâr", "status": "ready" if adapter.ready else "degraded"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "7860")))
