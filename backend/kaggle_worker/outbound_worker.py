from __future__ import annotations
import base64, io, os, subprocess, sys, threading, time, uuid
from pathlib import Path
import torch
import websocket
from PIL import Image

ROOT=Path("/kaggle/working/Kemzy-LiveAvatar")
PERSONALIVE_ROOT=Path("/kaggle/working/PersonaLive")
BACKEND=ROOT/"backend"
PINNED_PERSONALIVE="abdd112e01dcf7d89122c2e5efa29fcff0669740"
RENDER_URL=os.environ["KEMZY_RENDER_WS_URL"].rstrip("/")
WORKER_SECRET=os.environ["GPU_WORKER_SECRET"]
WORKER_ID=os.getenv("KEMZY_GPU_WORKER_ID",f"kaggle-{uuid.uuid4().hex[:12]}")
MODEL_DIR=os.getenv("MODEL_DIR","/kaggle/working/PersonaLive/pretrained_weights")
os.environ["MODEL_DIR"]=MODEL_DIR
os.environ["PERSONALIVE_COMMIT"]=PINNED_PERSONALIVE
sys.path.insert(0,str(PERSONALIVE_ROOT)); sys.path.insert(0,str(BACKEND))
PIPELINE=None; APP_ARGS=None; SESSIONS={}; PIPELINE_LOCK=threading.Lock()

def run(cmd,cwd=None):
    print("$"," ".join(cmd),flush=True); subprocess.run(cmd,cwd=str(cwd) if cwd else None,check=True)

def ensure_personalive():
    if not PERSONALIVE_ROOT.exists():
        run(["git","clone","https://github.com/GVCLab/PersonaLive.git",str(PERSONALIVE_ROOT)])
        run(["git","checkout",PINNED_PERSONALIVE],cwd=PERSONALIVE_ROOT)
    else:
        run(["git","fetch","--depth","1","origin",PINNED_PERSONALIVE],cwd=PERSONALIVE_ROOT)
        run(["git","checkout",PINNED_PERSONALIVE],cwd=PERSONALIVE_ROOT)
    req=PERSONALIVE_ROOT/"requirements_base.txt"
    filtered=PERSONALIVE_ROOT/"requirements_kaggle.txt"
    kept=[]
    for line in req.read_text(encoding="utf-8").splitlines():
        s=line.strip().lower()
        if s.startswith("torch==") or s.startswith("torch ") or s.startswith("torchvision==") or s.startswith("torchvision "):
            continue
        kept.append(line)
    filtered.write_text("\n".join(kept)+"\n",encoding="utf-8")
    run([sys.executable,"-m","pip","install","-q","-r",str(filtered)])
    run([sys.executable,"-m","pip","install","-q","websocket-client>=1.8,<2"])

def load_pipeline():
    global PIPELINE,APP_ARGS
    os.chdir(PERSONALIVE_ROOT)
    from webcam.config import Args
    from webcam.vid2vid import Pipeline
    APP_ARGS=Args(host="127.0.0.1",port=7860,reload=False,mode="default",max_queue_size=4,timeout=0.0,safety_checker=False,taesd=True,ssl_certfile=None,ssl_keyfile=None,debug=False,acceleration=os.getenv("ACCELERATION","xformers"),engine_dir=os.getenv("ENGINE_DIR","engines"),config_path=os.getenv("PERSONALIVE_CONFIG","./configs/prompts/personalive_online.yaml"))
    if not torch.cuda.is_available(): raise RuntimeError("CUDA GPU is required")
    torch.cuda.set_device(0)
    PIPELINE=Pipeline(APP_ARGS,torch.device("cuda:0"))
    print("PERSONALIVE_PIPELINE_READY",flush=True); print("GPU:",torch.cuda.get_device_name(0),flush=True)

def first_video_frame(data):
    import tempfile,cv2
    with tempfile.NamedTemporaryFile(suffix=".mp4") as h:
        h.write(data); h.flush(); cap=cv2.VideoCapture(h.name); ok,frame=cap.read(); cap.release()
    if not ok: raise ValueError("Could not decode the uploaded video")
    return Image.fromarray(cv2.cvtColor(frame,cv2.COLOR_BGR2RGB))

def decode_source(data,content_type):
    return first_video_frame(data) if content_type.startswith("video/") else Image.open(io.BytesIO(data)).convert("RGB")

def prepare_source(payload):
    sid=payload["session_id"]; raw=base64.b64decode(payload["data_base64"]) if payload.get("data_base64") else None
    if raw: SESSIONS[sid]=decode_source(raw,payload.get("content_type","image/jpeg"))
    elif sid not in SESSIONS: raise ValueError("Source image has not been uploaded")
    return {"status":"ready","worker_id":WORKER_ID,"session_id":sid}

def render_frame(payload):
    sid=payload["session_id"]; images=payload.get("driving_images") or []
    if sid not in SESSIONS: raise ValueError("Unknown source session")
    if not images: raise ValueError("driving_images must contain camera frame(s)")
    with PIPELINE_LOCK:
        PIPELINE.fuse_reference(SESSIONS[sid])
        from webcam.util import bytes_to_tensor
        for encoded in images:
            p=PIPELINE.InputParams(); p.image=bytes_to_tensor(base64.b64decode(encoded)); PIPELINE.accept_new_params(p)
        deadline=time.monotonic()+float(os.getenv("RENDER_TIMEOUT_SECONDS","120")); generated=[]
        while time.monotonic()<deadline:
            generated=PIPELINE.produce_outputs()
            if generated: break
            time.sleep(.01)
        if not generated: raise TimeoutError("PersonaLive produced no output frame before timeout")
        out=io.BytesIO(); generated[0].convert("RGB").save(out,format="JPEG",quality=85)
        return {"status":"rendered","worker_id":WORKER_ID,"mime_type":"image/jpeg","image_base64":base64.b64encode(out.getvalue()).decode("ascii"),"generated_frames":len(generated)}

def process(payload):
    a=payload.get("action")
    if a=="PREPARE_SOURCE": return {"status":"ready","worker_id":WORKER_ID} if "data_base64" not in payload else prepare_source(payload)
    if a=="RENDER_FRAME": return render_frame(payload)
    raise ValueError(f"Unknown action: {a}")

def run_connection():
    def on_open(ws):
        import json
        ws.send(json.dumps({"type":"register","worker_id":WORKER_ID,"gpu":torch.cuda.get_device_name(0),"cuda":torch.version.cuda,"renderer":"PersonaLive","personalive_commit":PINNED_PERSONALIVE}))
        print("KAGGLE_GPU_WORKER_ONLINE",flush=True)
    def on_message(ws,message):
        import json
        msg=json.loads(message)
        if msg.get("type")=="registered": print("BROKER_REGISTERED",flush=True); return
        if msg.get("type")!="job": return
        try: result=process(msg.get("data",{}))
        except Exception as exc: result={"status":"failed","error":f"{type(exc).__name__}: {exc}"}
        ws.send(json.dumps({"type":"result","jobId":msg.get("jobId"),"result":result}))
    def on_error(ws,error): print("BROKER_SOCKET_ERROR:",error,flush=True)
    def on_close(ws,code,reason): print("BROKER_SOCKET_CLOSED:",code,reason,flush=True)
    while True:
        ws=websocket.WebSocketApp(RENDER_URL+"/gpu-bridge",header=[f"X-Worker-Auth: {WORKER_SECRET}"],on_open=on_open,on_message=on_message,on_error=on_error,on_close=on_close)
        threading.Thread(target=lambda: (time.sleep(15),None),daemon=True).start()
        ws.run_forever(ping_interval=20,ping_timeout=10); time.sleep(3)

if __name__=="__main__":
    print("=== KÉMZY KAGGLE OUTBOUND GPU WORKER ===",flush=True)
    print("CUDA:",torch.cuda.is_available(),flush=True)
    ensure_personalive(); load_pipeline(); run_connection()
