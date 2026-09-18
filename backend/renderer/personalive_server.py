from __future__ import annotations

import argparse
import base64
import io
import os
import tempfile
import uuid
from pathlib import Path

import torch
from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from PIL import Image
from pydantic import BaseModel

from webcam.config import Args
from webcam.vid2vid import Pipeline

app = FastAPI(title="Kémzy àvátâr — PersonaLive Neural Renderer", version="1.0.0")

class Session:
    def __init__(self, pipeline: Pipeline):
        self.pipeline = pipeline

SESSIONS: dict[str, Session] = {}

class SessionCreate(BaseModel):
    source_type: str = "image"

def encode_jpeg(image: Image.Image) -> bytes:
    out = io.BytesIO()
    image.convert("RGB").save(out, format="JPEG", quality=85, optimize=True)
    return out.getvalue()

def make_pipeline(args: argparse.Namespace) -> Pipeline:
    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    if device.type != "cuda":
        raise RuntimeError("PersonaLive requires a CUDA GPU for the Kémzy renderer")
    cfg = Args(
        host=args.host,
        port=args.port,
        reload=False,
        mode="default",
        max_queue_size=1,
        timeout=0.0,
        safety_checker=False,
        taesd=False,
        ssl_certfile=None,
        ssl_keyfile=None,
        debug=False,
        acceleration=args.acceleration,
        engine_dir=args.engine_dir,
        config_path=args.config_path,
    )
    return Pipeline(cfg, device)

@app.get("/health")
def health():
    return {
        "status": "ready" if torch.cuda.is_available() else "degraded",
        "service": "kemzy-personalive-neural-renderer",
        "renderer": "PersonaLive",
        "personalive_commit": os.getenv("PERSONALIVE_COMMIT", "abdd112e01dcf7d89122c2e5efa29fcff0669740"),
        "cuda": torch.cuda.is_available(),
    }

@app.get("/ready")
def ready():
    return health()

@app.post("/v1/sessions")
def create_session(request: SessionCreate):
    if request.source_type != "image":
        raise HTTPException(status_code=422, detail="PersonaLive live reference mode requires an image source")
    session_id = str(uuid.uuid4())
    try:
        pipeline = make_pipeline(APP_ARGS)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"PersonaLive initialization failed: {exc}") from exc
    SESSIONS[session_id] = Session(pipeline)
    return {"session_id": session_id, "renderer": "PersonaLive", "status": "created"}

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
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"PersonaLive reference preparation failed: {exc}") from exc
    return {"session_id": session_id, "status": "source_ready", "renderer": "PersonaLive"}

@app.websocket("/v1/stream/{session_id}")
async def stream(session_id: str, websocket: WebSocket):
    session = SESSIONS.get(session_id)
    if session is None:
        await websocket.close(code=1008)
        return
    await websocket.accept()
    try:
        while True:
            message = await websocket.receive()
            if "text" in message:
                continue
            data = message.get("bytes")
            if not data:
                continue
            from webcam.util import bytes_to_tensor
            params = type("Params", (), {})()
            params.image = bytes_to_tensor(data)
            session.pipeline.accept_new_params(params)
            frames = session.pipeline.produce_outputs()
            for frame in frames:
                await websocket.send_bytes(encode_jpeg(frame))
    except WebSocketDisconnect:
        return

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.getenv("HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.getenv("PORT", "7860")))
    parser.add_argument("--acceleration", choices=["none", "xformers", "tensorrt"], default=os.getenv("ACCELERATION", "xformers"))
    parser.add_argument("--engine-dir", default=os.getenv("ENGINE_DIR", "engines"))
    parser.add_argument("--config_path", default=os.getenv("PERSONALIVE_CONFIG", "./configs/prompts/personalive_online.yaml"))
    return parser.parse_args()

APP_ARGS = parse_args()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=APP_ARGS.host, port=APP_ARGS.port)
