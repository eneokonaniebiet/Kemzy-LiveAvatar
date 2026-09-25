from __future__ import annotations

import base64
import os
import uuid
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field, ValidationError

from .gpu_broker import GPUWorkerBroker

app = FastAPI(title="Kémzy àvátâr API", version="0.6.0")

# Reuse the existing internal renderer token when present, so this change does
# not require exposing a GPU URL. A dedicated GPU_WORKER_SECRET can override it.
GPU_WORKER_SECRET = (os.getenv("GPU_WORKER_SECRET") or os.getenv("GPU_RENDERER_TOKEN") or "").strip()
BROKER = GPUWorkerBroker(GPU_WORKER_SECRET)
_SESSION_WORKERS: dict[str, str] = {}


class SessionCreate(BaseModel):
    source_type: str = Field(pattern="^(image|video)$")


class MotionFrame(BaseModel):
    timestamp_ms: int = Field(ge=0)
    pose: list[float] = Field(default_factory=lambda: [0.0, 0.0, 0.0])
    expression: list[float] = Field(default_factory=lambda: [0.0] * 63)
    landmarks: list[float] = Field(default_factory=list)
    eye_ratio: float | None = Field(default=None, ge=0.0, le=1.0)
    lip_ratio: float | None = Field(default=None, ge=0.0, le=1.0)
    driving_images: list[str] = Field(default_factory=list)

    def validate_driver(self) -> None:
        if len(self.pose) != 3:
            raise ValueError("pose must contain exactly 3 values")
        if len(self.expression) != 63:
            raise ValueError("expression must contain exactly 63 values")
        if len(self.landmarks) % 3 != 0:
            raise ValueError("landmarks must contain x,y,z triplets")
        if not self.driving_images:
            raise ValueError("driving_images must contain at least one camera frame")


class DriverFrame(BaseModel):
    timestamp_ms: int
    yaw: float = 0.0
    pitch: float = 0.0
    roll: float = 0.0
    eye_left: float = Field(default=0.0, ge=0.0, le=1.0)
    eye_right: float = Field(default=0.0, ge=0.0, le=1.0)
    mouth_open: float = Field(default=0.0, ge=0.0, le=1.0)
    smile: float = Field(default=0.0, ge=0.0, le=1.0)
    brow_left: float = Field(default=0.0, ge=0.0, le=1.0)
    brow_right: float = Field(default=0.0, ge=0.0, le=1.0)
    driving_images: list[str] = Field(default_factory=list)

    def to_motion(self) -> MotionFrame:
        expression = [0.0] * 63
        expression[0] = self.eye_left
        expression[3] = self.eye_right
        expression[18] = self.mouth_open
        expression[21] = self.smile
        expression[36] = self.brow_left
        expression[39] = self.brow_right
        return MotionFrame(
            timestamp_ms=self.timestamp_ms,
            pose=[self.pitch, self.yaw, self.roll],
            expression=expression,
            driving_images=self.driving_images,
            eye_ratio=(self.eye_left + self.eye_right) * 0.5,
            lip_ratio=self.mouth_open,
        )


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "service": "kemzy-api",
        "version": app.version,
        **BROKER.snapshot(),
    }


@app.get("/ready")
def ready() -> dict[str, Any]:
    snapshot = BROKER.snapshot()
    online = snapshot["workers_connected"] > 0
    return {
        "status": "ready" if online else "degraded",
        "renderer": "FasterLivePortrait",
        "backend": "FasterLivePortrait1",
        "upstream": "hidden",
        **snapshot,
    }


@app.websocket("/gpu-bridge")
async def gpu_bridge(websocket: WebSocket) -> None:
    if not GPU_WORKER_SECRET or websocket.headers.get("x-worker-auth", "") != GPU_WORKER_SECRET:
        await websocket.close(code=1008)
        return

    await websocket.accept()
    worker_id = ""
    try:
        hello = await websocket.receive_json()
        if hello.get("type") != "register":
            await websocket.close(code=1008)
            return

        worker_id = str(hello.get("worker_id") or uuid.uuid4())
        await BROKER.register(websocket, worker_id)
        await websocket.send_json({
            "type": "registered",
            "worker_id": worker_id,
            "status": "online",
        })

        while True:
            message = await websocket.receive_json()
            kind = message.get("type")
            if kind == "heartbeat":
                await BROKER.heartbeat(worker_id)
                await websocket.send_json({"type": "heartbeat_ack"})
            elif kind == "result":
                await BROKER.heartbeat(worker_id)
                await BROKER.complete(str(message.get("jobId", "")), message.get("result", {}))
            elif kind == "worker_status":
                await BROKER.heartbeat(worker_id)
    except WebSocketDisconnect:
        pass
    finally:
        if worker_id:
            await BROKER.unregister(worker_id, websocket)


@app.post("/v1/sessions")
async def create_session(request: SessionCreate) -> dict[str, Any]:
    if BROKER.snapshot()["workers_connected"] == 0:
        raise HTTPException(status_code=503, detail="GPU_TEMPORARILY_UNAVAILABLE")

    session_id = str(uuid.uuid4())
    # Registration establishes the session affinity. No source bytes are sent here.
    try:
        result = await BROKER.submit(
            "CREATE_SESSION",
            {"session_id": session_id, "source_type": request.source_type},
        )
    except Exception as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    if result.get("status") != "ready":
        raise HTTPException(status_code=502, detail=result.get("error", "GPU session initialization failed"))

    _SESSION_WORKERS[session_id] = str(result.get("worker_id", ""))
    return {
        "session_id": session_id,
        "source_type": request.source_type,
        "renderer": "gpu",
        "status": "created",
    }


@app.post("/v1/sessions/{session_id}/source")
async def upload_source(session_id: str, file: UploadFile = File(...)) -> dict[str, Any]:
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="Empty source file")
    if len(data) > 100 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Source file exceeds 100 MB")

    payload = {
        "session_id": session_id,
        "content_type": file.content_type or "application/octet-stream",
        "data_base64": base64.b64encode(data).decode("ascii"),
    }

    try:
        result = await BROKER.submit(
            "PREPARE_SOURCE",
            payload,
            timeout=float(os.getenv("SOURCE_TIMEOUT_SECONDS", "180")),
        )
    except Exception as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    if result.get("status") != "ready":
        raise HTTPException(status_code=502, detail=result.get("error", "GPU source preparation failed"))

    _SESSION_WORKERS[session_id] = str(result.get("worker_id", ""))
    return {
        "session_id": session_id,
        "status": "source_ready",
        "renderer": "kaggle-gpu",
        "source_handle": session_id,
        "source_type": "video" if (file.content_type or "").startswith("video/") else "image",
    }


@app.post("/v1/render/frame")
async def render_frame(frame: MotionFrame, session_id: str = "") -> dict[str, Any]:
    if not session_id:
        raise HTTPException(status_code=422, detail="session_id is required")
    try:
        frame.validate_driver()
        result = await BROKER.submit(
            "RENDER_FRAME",
            {"session_id": session_id, **frame.model_dump()},
            timeout=float(os.getenv("RENDER_TIMEOUT_SECONDS", "120")),
        )
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    if result.get("status") != "rendered":
        raise HTTPException(status_code=502, detail=result.get("error", "GPU renderer failed"))

    return {"session_id": session_id, **result}


@app.websocket("/v1/stream/{session_id}")
async def stream_motion(websocket: WebSocket, session_id: str) -> None:
    await websocket.accept()
    if session_id not in _SESSION_WORKERS:
        await websocket.send_json({"type": "error", "code": "source_not_ready"})
        await websocket.close(code=1008)
        return

    try:
        while True:
            payload = await websocket.receive_json()
            try:
                if payload.get("type") == "driver":
                    frame = DriverFrame.model_validate(payload).to_motion()
                else:
                    frame = MotionFrame.model_validate(payload)
                frame.validate_driver()
            except (ValidationError, ValueError) as exc:
                await websocket.send_json({
                    "type": "error",
                    "code": "invalid_motion",
                    "message": str(exc),
                })
                continue

            try:
                result = await BROKER.submit(
                    "RENDER_FRAME",
                    {"session_id": session_id, **frame.model_dump()},
                    timeout=float(os.getenv("RENDER_TIMEOUT_SECONDS", "120")),
                )
            except Exception as exc:
                await websocket.send_json({
                    "type": "error",
                    "code": "render_failed",
                    "message": str(exc),
                })
                continue

            await websocket.send_json({
                "type": "frame",
                "session_id": session_id,
                "timestamp_ms": frame.timestamp_ms,
                "mime_type": result.get("mime_type", "image/jpeg"),
                "frame_base64": result.get("image_base64", ""),
            })
    except WebSocketDisconnect:
        return
