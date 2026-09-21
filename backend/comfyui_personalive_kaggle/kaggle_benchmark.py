#!/usr/bin/env python3
"""
Kémzy isolated ComfyUI + PersonaLive Kaggle benchmark.

This script is intentionally independent from the existing Render/Cloudflare
gateway. It validates the GPU execution engine before any production wiring.

Expected environment:
  Kaggle notebook, NVIDIA T4/T4x2, Internet enabled.

Optional:
  KEMZY_PERSONALIVE_MODELS=/path/to/persona_live
  KEMZY_COMFY_DIR=/kaggle/working/kemzy-comfyui
  KEMZY_PORT=8188
"""

import base64
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.parse import urlencode

ROOT = Path(os.environ.get("KEMZY_COMFY_DIR", "/kaggle/working/kemzy-comfyui"))
PORT = int(os.environ.get("KEMZY_PORT", "8188"))
HOST = "127.0.0.1"
COMFY_URL = f"http://{HOST}:{PORT}"
NODE_REPO = "https://github.com/okdalto/ComfyUI-PersonaLive.git"
COMFY_REPO = "https://github.com/Comfy-Org/ComfyUI.git"
ASSET_URL = "https://raw.githubusercontent.com/okdalto/ComfyUI-PersonaLive/main/assets/main.jpg"
TEST_IMAGE = ROOT / "input" / "kemzy_personalive_test.jpg"
LOG = ROOT / "comfy.log"

def run(cmd, cwd=None, env=None):
    print("\n$ " + " ".join(map(str, cmd)), flush=True)
    subprocess.run(cmd, cwd=cwd, env=env, check=True)

def http_json(path, method="GET", payload=None):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = Request(COMFY_URL + path, data=data, headers=headers, method=method)
    with urlopen(req, timeout=60) as r:
        return json.loads(r.read())

def download(url, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    with urlopen(url, timeout=120) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)

def install_environment():
    ROOT.mkdir(parents=True, exist_ok=True)
    if not (ROOT / "ComfyUI" / "main.py").exists():
        run(["git", "clone", "--depth", "1", COMFY_REPO, str(ROOT / "ComfyUI")])
    comfy = ROOT / "ComfyUI"
    custom = comfy / "custom_nodes" / "ComfyUI-PersonaLive"
    if not custom.exists():
        run(["git", "clone", "--depth", "1", NODE_REPO, str(custom)])

    # Keep Kaggle's working CUDA/PyTorch installation. The node only needs
    # Python packages around it; xformers/TensorRT/pycuda are optional for
    # this validation and are intentionally not forced onto the environment.
    packages = [
        "accelerate", "av", "decord", "diffusers", "einops",
        "huggingface-hub", "mediapipe", "omegaconf",
        "opencv-python-headless", "Pillow", "safetensors",
        "tqdm", "transformers", "websocket-client", "requests"
    ]
    run([sys.executable, "-m", "pip", "install", "-q", *packages])
    return comfy

def prepare_models(comfy):
    external = os.environ.get("KEMZY_PERSONALIVE_MODELS")
    target = comfy / "models" / "persona_live"
    if external:
        src = Path(external)
        if not src.exists():
            raise RuntimeError(f"KEMZY_PERSONALIVE_MODELS does not exist: {src}")
        # ComfyUI-PersonaLive expects the model directory itself to contain
        # sd-image-variations-diffusers, sd-vae-ft-mse and persona_live.
        if target.exists():
            shutil.rmtree(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(src, target)
        print(f"Using supplied PersonaLive model tree: {target}")
    else:
        target.mkdir(parents=True, exist_ok=True)
        print("No preloaded model directory supplied; the custom node will download")
        print("the required base/VAE/PersonaLive weights on first checkpoint load.")
    return target

def start_comfy(comfy):
    env = os.environ.copy()
    env["PYTHONUNBUFFERED"] = "1"
    with open(LOG, "w", buffering=1) as log:
        proc = subprocess.Popen(
            [sys.executable, "main.py", "--listen", HOST, "--port", str(PORT),
             "--disable-auto-launch"],
            cwd=comfy, env=env, stdout=log, stderr=subprocess.STDOUT
        )
    deadline = time.time() + 180
    last = ""
    while time.time() < deadline:
        if proc.poll() is not None:
            print(LOG.read_text(errors="replace")[-12000:])
            raise RuntimeError("ComfyUI exited during startup")
        try:
            obj = http_json("/system_stats")
            if obj:
                print("ComfyUI API is ready.")
                return proc
        except Exception as e:
            last = str(e)
        time.sleep(2)
    print(LOG.read_text(errors="replace")[-12000:])
    raise RuntimeError(f"ComfyUI did not become ready: {last}")

def upload_image(comfy, image_path):
    import requests
    with open(image_path, "rb") as f:
        r = requests.post(
            COMFY_URL + "/upload/image",
            files={"image": (image_path.name, f, "image/jpeg")},
            data={"type": "input", "overwrite": "true"},
            timeout=120,
        )
    r.raise_for_status()
    return r.json()["name"]

def workflow(filename, seed):
    # One source image is repeated to a 4-frame driving batch. This is a
    # pipeline/latency proof, not an expression-quality proof.
    return {
        "1": {"class_type": "LoadImage", "inputs": {"image": filename}},
        "2": {"class_type": "RepeatImageBatch",
              "inputs": {"image": ["1", 0], "amount": 4}},
        "3": {"class_type": "PersonaLiveCheckpointLoader",
              "inputs": {"model_dir": "persona_live"}},
        "4": {"class_type": "PersonaLivePhotoSampler",
              "inputs": {
                  "pipe": ["3", 0],
                  "ref_image": ["1", 0],
                  "driving_image": ["2", 0],
                  "width": 512,
                  "height": 512,
                  "guidance_scale": 1.0,
                  "seed": seed,
              }},
        "5": {"class_type": "SaveImage",
              "inputs": {"images": ["4", 0], "filename_prefix": "kemzy_personalive_benchmark"}},
    }

def run_prompt(prompt):
    client_id = str(uuid.uuid4())
    ws_url = f"ws://{HOST}:{PORT}/ws?clientId={client_id}"
    import websocket
    ws = websocket.create_connection(ws_url, timeout=300)
    try:
        queued = http_json("/prompt", method="POST",
                           payload={"prompt": prompt, "client_id": client_id})
        prompt_id = queued["prompt_id"]
        started = time.perf_counter()
        while True:
            raw = ws.recv()
            if isinstance(raw, str):
                msg = json.loads(raw)
                if msg.get("type") == "executing":
                    data = msg.get("data", {})
                    if data.get("prompt_id") == prompt_id and data.get("node") is None:
                        break
        elapsed = time.perf_counter() - started
        history = http_json("/history/" + prompt_id)
        if prompt_id not in history:
            raise RuntimeError("Prompt completed but no history was returned")
        return elapsed, history[prompt_id]
    finally:
        ws.close()

def cuda_stats():
    try:
        import torch
        if not torch.cuda.is_available():
            return {"cuda": False}
        return {
            "cuda": True,
            "torch": torch.__version__,
            "gpu": torch.cuda.get_device_name(0),
            "allocated_mb": round(torch.cuda.memory_allocated(0) / 2**20, 1),
            "reserved_mb": round(torch.cuda.memory_reserved(0) / 2**20, 1),
            "max_allocated_mb": round(torch.cuda.max_memory_allocated(0) / 2**20, 1),
            "max_reserved_mb": round(torch.cuda.max_memory_reserved(0) / 2**20, 1),
        }
    except Exception as e:
        return {"cuda_stats_error": str(e)}

def main():
    print("=== KÉMZY COMFYUI + PERSONAlive GPU BENCHMARK ===")
    print("This test does NOT modify Render, Cloudflare, or the APK.")

    import torch
    print("Torch:", torch.__version__)
    print("CUDA:", torch.cuda.is_available())
    if not torch.cuda.is_available():
        raise SystemExit("FAIL: CUDA is not available. Select Kaggle T4/T4x2.")

    comfy = install_environment()
    prepare_models(comfy)

    TEST_IMAGE.parent.mkdir(parents=True, exist_ok=True)
    if not TEST_IMAGE.exists():
        print("Downloading deterministic face test image...")
        download(ASSET_URL, TEST_IMAGE)

    proc = start_comfy(comfy)
    try:
        print("\n=== CUSTOM NODE DISCOVERY ===")
        info = http_json("/object_info/PersonaLiveCheckpointLoader")
        print("PersonaLiveCheckpointLoader:", "OK" if info else "MISSING")
        info2 = http_json("/object_info/PersonaLivePhotoSampler")
        print("PersonaLivePhotoSampler:", "OK" if info2 else "MISSING")

        filename = upload_image(comfy, TEST_IMAGE)

        print("\n=== FIRST REAL PERSONaLIVE RUN ===")
        torch.cuda.reset_peak_memory_stats()
        t0 = time.perf_counter()
        elapsed, history = run_prompt(workflow(filename, 42))
        wall = time.perf_counter() - t0
        print(f"First run execution: {elapsed:.3f}s")
        print(f"First run wall time: {wall:.3f}s")
        print("First CUDA stats:", cuda_stats())

        outputs = history.get("outputs", {})
        output_count = sum(len(v.get("images", [])) for v in outputs.values())
        print("Output images:", output_count)
        if output_count < 1:
            raise RuntimeError("FAIL: no rendered image was returned")

        print("\n=== WARM RUNS (MODEL MUST STAY LOADED) ===")
        warm = []
        for i in range(3):
            torch.cuda.reset_peak_memory_stats()
            t0 = time.perf_counter()
            elapsed, history = run_prompt(workflow(filename, 100 + i))
            wall = time.perf_counter() - t0
            stats = cuda_stats()
            count = sum(len(v.get("images", [])) for v in history.get("outputs", {}).values())
            row = {"run": i + 1, "execution_s": round(elapsed, 3),
                   "wall_s": round(wall, 3), "output_images": count, **stats}
            warm.append(row)
            print(json.dumps(row))

        result = {
            "status": "PASS",
            "first_execution_s": round(first_elapsed, 3),
            "first_wall_s": round(first_wall, 3),
            "warm_runs": warm,
            "torch": torch.__version__,
            "gpu": torch.cuda.get_device_name(0),
            "note": "Four identical driving frames prove batch execution only; live FPS still requires a network/session benchmark.",
        }
        out = ROOT / "kemzy_personalive_benchmark.json"
        out.write_text(json.dumps(result, indent=2))
        print("\n=== FINAL RESULT ===")
        print(json.dumps(result, indent=2))
        print(f"Saved: {out}")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()

if __name__ == "__main__":
    main()
