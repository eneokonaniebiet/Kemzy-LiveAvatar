import os
import uuid
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field, ValidationError

from .renderer_client import RendererClient

app = FastAPI(title='Kémzy àvátâr API', version='0.5.0')
GPU_RENDERER_URL = os.getenv('GPU_RENDERER_URL', '').rstrip('/')
GPU_RENDERER_TOKEN = os.getenv('GPU_RENDERER_TOKEN', '')
_RENDERER = RendererClient(GPU_RENDERER_URL, GPU_RENDERER_TOKEN) if GPU_RENDERER_URL else None
_SESSION_HANDLES: dict[str, str] = {}


class SessionCreate(BaseModel):
    source_type: str = Field(pattern='^(image|video)$')


class MotionFrame(BaseModel):
    timestamp_ms: int = Field(ge=0)
    pose: list[float]
    expression: list[float]
    landmarks: list[float] = Field(default_factory=list)
    eye_ratio: float | None = Field(default=None, ge=0.0, le=1.0)
    lip_ratio: float | None = Field(default=None, ge=0.0, le=1.0)

    def validate_driver(self) -> None:
        if len(self.pose) != 3:
            raise ValueError('pose must contain exactly 3 values')
        if len(self.expression) != 63:
            raise ValueError('expression must contain exactly 63 values')
        if len(self.landmarks) % 3 != 0:
            raise ValueError('landmarks must contain x,y,z triplets')



class DriverFrame(BaseModel):
    timestamp_ms: int = Field(ge=0)
    yaw: float = 0.0
    pitch: float = 0.0
    roll: float = 0.0
    eye_left: float = Field(default=0.0, ge=0.0, le=1.0)
    eye_right: float = Field(default=0.0, ge=0.0, le=1.0)
    mouth_open: float = Field(default=0.0, ge=0.0, le=1.0)
    smile: float = Field(default=0.0, ge=0.0, le=1.0)
    brow_left: float = Field(default=0.0, ge=0.0, le=1.0)
    brow_right: float = Field(default=0.0, ge=0.0, le=1.0)

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
            landmarks=[],
            eye_ratio=(self.eye_left + self.eye_right) * 0.5,
            lip_ratio=self.mouth_open,
        )

IMAGE_TYPES = {'image/jpeg', 'image/png', 'image/webp'}
VIDEO_TYPES = {'video/mp4', 'video/webm', 'video/quicktime', 'video/x-m4v'}
SOURCE_TYPES = IMAGE_TYPES | VIDEO_TYPES


@app.get('/health')
def health() -> dict[str, Any]:
    return {'status': 'ok', 'service': 'kemzy-api', 'version': app.version}


@app.get('/ready')
async def ready() -> dict[str, Any]:
    if _RENDERER is None:
        return {'status': 'degraded', 'renderer': 'not_configured'}
    renderer = await _RENDERER.health()
    return {
        'status': 'ready' if renderer.status == 'ready' else 'degraded',
        'renderer': GPU_RENDERER_URL,
        'backend': renderer.backend,
        'error': renderer.error,
    }


@app.post('/v1/sessions')
def create_session(request: SessionCreate) -> dict[str, Any]:
    session_id = str(uuid.uuid4())
    return {'session_id': session_id, 'source_type': request.source_type, 'renderer': 'gpu', 'status': 'created'}


@app.post('/v1/sessions/{session_id}/source')
async def upload_source(session_id: str, file: UploadFile = File(...)) -> dict[str, Any]:
    content_type = file.content_type or ''
    if content_type not in SOURCE_TYPES:
        raise HTTPException(status_code=415, detail='Source must be a supported image or video')
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail='Empty source file')
    max_size = 100 * 1024 * 1024 if content_type in VIDEO_TYPES else 25 * 1024 * 1024
    if len(data) > max_size:
        raise HTTPException(status_code=413, detail=f'Source file exceeds {max_size // (1024 * 1024)} MB')

    if _RENDERER is None:
        return {'session_id': session_id, 'status': 'accepted', 'renderer': 'pending'}

    source_type = 'video' if content_type in VIDEO_TYPES else 'image'
    filename = file.filename or ('source.mp4' if source_type == 'video' else 'source.jpg')
    try:
        handle = await _RENDERER.prepare_source(
            data,
            content_type,
            source_type=source_type,
            filename=filename,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f'GPU renderer rejected source: {exc}') from exc

    _SESSION_HANDLES[session_id] = handle
    return {
        'session_id': session_id,
        'status': 'source_ready',
        'renderer': 'zerogpu',
        'source_handle': handle,
        'source_type': source_type,
    }


async def _render_motion(session_id: str, frame: MotionFrame) -> dict[str, Any]:
    frame.validate_driver()
    if _RENDERER is None:
        raise RuntimeError('GPU renderer is not configured')
    source_handle = _SESSION_HANDLES.get(session_id)
    if not source_handle:
        raise RuntimeError('Source has not been prepared for this session')
    return await _RENDERER.render_frame(
        source_handle,
        frame.pose,
        frame.expression,
        frame.landmarks,
        frame.eye_ratio,
        frame.lip_ratio,
        frame.timestamp_ms,
    )


@app.post('/v1/render/frame')
async def render_frame(frame: MotionFrame, session_id: str = '') -> dict[str, Any]:
    if not session_id:
        raise HTTPException(status_code=422, detail='session_id is required')
    try:
        result = await _render_motion(session_id, frame)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f'GPU renderer failed: {exc}') from exc
    return {'session_id': session_id, 'timestamp_ms': frame.timestamp_ms, **result}


@app.websocket('/v1/stream/{session_id}')
async def stream_motion(websocket: WebSocket, session_id: str) -> None:
    await websocket.accept()
    if session_id not in _SESSION_HANDLES:
        await websocket.send_json({'type': 'error', 'code': 'source_not_ready', 'message': 'Source has not been prepared for this session'})
        await websocket.close(code=1008)
        return
    if _RENDERER is None:
        await websocket.send_json({'type': 'error', 'code': 'renderer_unavailable', 'message': 'GPU renderer is not configured'})
        await websocket.close(code=1013)
        return

    try:
        while True:
            payload = await websocket.receive_json()
            try:
                if payload.get('type') == 'driver':
                    frame = DriverFrame.model_validate(payload).to_motion()
                else:
                    frame = MotionFrame.model_validate(payload)
                frame.validate_driver()
            except (ValidationError, ValueError) as exc:
                await websocket.send_json({'type': 'error', 'code': 'invalid_motion', 'message': str(exc)})
                continue

            try:
                result = await _render_motion(session_id, frame)
            except Exception as exc:
                await websocket.send_json({'type': 'error', 'code': 'render_failed', 'message': str(exc)})
                continue

            await websocket.send_json({
                'type': 'frame',
                'session_id': session_id,
                'timestamp_ms': frame.timestamp_ms,
                'mime_type': result.get('mime_type', 'image/png'),
                'frame_base64': result.get('image_base64', ''),
            })
    except WebSocketDisconnect:
        return
