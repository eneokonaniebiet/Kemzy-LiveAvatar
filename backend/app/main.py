import os
import uuid
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel, Field

from .renderer_client import RendererClient

app = FastAPI(title='Kémzy àvátâr API', version='0.2.0')
GPU_RENDERER_URL = os.getenv('GPU_RENDERER_URL', '').rstrip('/')
GPU_RENDERER_TOKEN = os.getenv('GPU_RENDERER_TOKEN', '')
_RENDERER = RendererClient(GPU_RENDERER_URL, GPU_RENDERER_TOKEN) if GPU_RENDERER_URL else None
_SESSION_HANDLES: dict[str, str] = {}

class SessionCreate(BaseModel):
    source_type: str = Field(pattern='^(image|video)$')

class MotionFrame(BaseModel):
    session_id: str
    timestamp_ms: int = Field(ge=0)
    pose: list[float] = Field(default_factory=list)
    expression: list[float] = Field(default_factory=list)
    landmarks: list[float] = Field(default_factory=list)

@app.get('/health')
def health() -> dict[str, Any]:
    return {'status': 'ok', 'service': 'kemzy-api', 'version': app.version}

@app.get('/ready')
async def ready() -> dict[str, Any]:
    if _RENDERER is None:
        return {'status': 'degraded', 'renderer': 'not_configured'}
    renderer = await _RENDERER.health()
    return {'status': 'ready' if renderer.status == 'ready' else 'degraded', 'renderer': GPU_RENDERER_URL, 'backend': renderer.backend, 'error': renderer.error}

@app.post('/v1/sessions')
def create_session(request: SessionCreate) -> dict[str, Any]:
    session_id = str(uuid.uuid4())
    return {'session_id': session_id, 'source_type': request.source_type, 'renderer': 'gpu', 'status': 'created'}

@app.post('/v1/sessions/{session_id}/source')
async def upload_source(session_id: str, file: UploadFile = File(...)) -> dict[str, Any]:
    if file.content_type not in {'image/jpeg', 'image/png', 'image/webp'}:
        raise HTTPException(status_code=415, detail='GPU renderer currently accepts image sources')
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail='Empty source file')
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(status_code=413, detail='Source file exceeds 25 MB')
    if _RENDERER is None:
        return {'session_id': session_id, 'status': 'accepted', 'renderer': 'pending'}
    try:
        handle = await _RENDERER.prepare_source(data, file.content_type)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f'GPU renderer rejected source: {exc}') from exc
    _SESSION_HANDLES[session_id] = handle
    return {'session_id': session_id, 'status': 'source_ready', 'renderer': 'zerogpu', 'source_handle': handle}

@app.post('/v1/render/frame')
async def render_frame(frame: MotionFrame) -> dict[str, Any]:
    if _RENDERER is None:
        raise HTTPException(status_code=503, detail='GPU renderer is not configured')
    source_handle = _SESSION_HANDLES.get(frame.session_id)
    if not source_handle:
        raise HTTPException(status_code=409, detail='Source has not been prepared for this session')
    try:
        result = await _RENDERER.render_frame(source_handle, frame.pose, frame.expression, frame.landmarks)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f'GPU renderer failed: {exc}') from exc
    return {'session_id': frame.session_id, 'timestamp_ms': frame.timestamp_ms, **result}
