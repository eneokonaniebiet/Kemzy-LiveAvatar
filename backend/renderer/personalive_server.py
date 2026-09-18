from __future__ import annotations

import argparse
import io
import os
import threading
import uuid

import torch
from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from PIL import Image

from webcam.config import Args
from webcam.util import bytes_to_tensor, pil_to_frame
from webcam.vid2vid import Pipeline

app = FastAPI(title="Kémzy àvátâr — PersonaLive", version="1.0.0")
SESSIONS = {}
LOCK = threading.Lock()
APP_ARGS = None

def build_args() -> Args:
    return Args(
        host=os.getenv("HOST", "0.0.0.0"),
        port=int(os.getenv("PORT", "7860")),
        reload=False,
        mode=os.getenv("MODE", "default"),
        max_queue_size=int(os.getenv("MAX_QUEUE_SIZE", "1")),
        timeout=float(os.getenv("TIMEOUT", "0")),
        safety_checker=os.getenv("SAFETY_CHECKER", "False") == "True",
        taesd=os.getenv("USE_TAESD", "True") == "True",
        ssl_certfile=None,
        ssl_keyfile=None,
        debug=False,
        acceleration=os.getenv("ACCELERATION", "xformers"),
        engine_dir=os.getenv("ENGINE_DIR", "engines"),
        config_path=os.getenv("PERSONALIVE_CONFIG", "./configs/prompts/personalive_online.yaml"),
    )

class Session:
    def __init__(self):
        device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
        if device.type != "cuda":
            raise RuntimeError("PersonaLive requires a CUDA GPU")
        pipeline_class = Pipeline
        self.pipeline = pipeline_class(APP_ARGS, device)
        self.source_ready = False

    def close(self):
        try:
            self.pipeline.close()
        except Exception:
            pass

@app.get("/health")
def health():
    return {
        "status": "ok" if torch.cuda.is_available() else "degraded",
        "renderer": "PersonaLive",
        "personalive_commit": os.getenv("PERSONALIVE_COMMIT", "abdd112e01dcf7d89122c2e5efa29fcff0669740"),
        "cuda": torch.cuda.is_available(),
        "acceleration": APP_ARGS.acceleration if APP_ARGS else os.getenv("ACCELERATION", "xformers"),
    }

@app.get("/ready")
def ready():
    return health()

@app.post("/v1/sessions")
def create_session():
    session_id = str(uuid.uuid4())
    try:
        session = Session()
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"PersonaLive initialization failed: {exc}") from exc
    with LOCK:
        SESSIONS[session_id] = session
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
        session.source_ready = True
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"PersonaLive reference preparation failed: {exc}") from exc
    return {"session_id": session_id, "status": "source_ready", "renderer": "PersonaLive"}

@app.post("/v1/sessions/{session_id}/reset")
def reset_session(session_id: str):
    session = SESSIONS.get(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Unknown session")
    session.pipeline.reset()
    session.source_ready = False
    return {"status": "reset"}

@app.websocket("/v1/stream/{session_id}")
async def stream(session_id: str, websocket: WebSocket):
    session = SESSIONS.get(session_id)
    if session is None or not session.source_ready:
        await websocket.close(code=1008)
        return
    await websocket.accept()
    try:
        while True:
            message = await websocket.receive()
            data = message.get("bytes")
            if not data:
                continue
            params = type("Params", (), {})()
            params.image = bytes_to_tensor(data)
            session.pipeline.accept_new_params(params)
            # PersonaLive internally generates streaming chunks; return every
            # available output frame without buffering old frames.
            for frame in session.pipeline.produce_outputs():
                await websocket.send_bytes(pil_to_frame(frame))
    except WebSocketDisconnect:
        return
    except Exception as exc:
        try:
            await websocket.send_json({"status": "error", "message": str(exc)})
        except Exception:
            pass

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.getenv("HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.getenv("PORT", "7860")))
    parser.add_argument("--acceleration", choices=["none", "xformers", "tensorrt"], default=os.getenv("ACCELERATION", "xformers"))
    parser.add_argument("--engine-dir", default=os.getenv("ENGINE_DIR", "engines"))
    parser.add_argument("--config_path", default=os.getenv("PERSONALIVE_CONFIG", "./configs/prompts/personalive_online.yaml"))
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
