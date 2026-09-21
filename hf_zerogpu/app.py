from __future__ import annotations

import io
import os
import subprocess
import sys
import threading
from pathlib import Path

import gradio as gr
import spaces
import torch
from PIL import Image

PERSONALIVE_COMMIT = "abdd112e01dcf7d89122c2e5efa29fcff0669740"
ROOT = Path(__file__).resolve().parent
REPO = ROOT / "PersonaLive"
WEIGHTS = REPO / "pretrained_weights"

_pipeline = None
_pipeline_lock = threading.Lock()


def ensure_personalive() -> None:
    if REPO.exists():
        return
    subprocess.run(
        [
            "git", "clone", "--filter=blob:none",
            "https://github.com/GVCLab/PersonaLive.git",
            str(REPO),
        ],
        check=True,
    )
    subprocess.run(
        ["git", "-C", str(REPO), "fetch", "--depth", "1", "origin", PERSONALIVE_COMMIT],
        check=True,
    )
    subprocess.run(
        ["git", "-C", str(REPO), "checkout", "--detach", PERSONALIVE_COMMIT],
        check=True,
    )


def ensure_weights() -> None:
    ensure_personalive()
    required = [
        WEIGHTS / "personalive" / "denoising_unet.pth",
        WEIGHTS / "personalive" / "motion_encoder.pth",
        WEIGHTS / "personalive" / "motion_extractor.pth",
        WEIGHTS / "personalive" / "pose_guider.pth",
        WEIGHTS / "personalive" / "reference_unet.pth",
        WEIGHTS / "personalive" / "temporal_module.pth",
        WEIGHTS / "sd-vae-ft-mse" / "diffusion_pytorch_model.bin",
        WEIGHTS / "sd-image-variations-diffusers" / "image_encoder" / "pytorch_model.bin",
        WEIGHTS / "sd-image-variations-diffusers" / "unet" / "diffusion_pytorch_model.bin",
    ]
    if all(p.is_file() for p in required):
        return
    subprocess.run(
        [sys.executable, "tools/download_weights.py"],
        cwd=str(REPO),
        check=True,
    )


def get_pipeline():
    global _pipeline
    ensure_personalive()
    ensure_weights()
    with _pipeline_lock:
        if _pipeline is None:
            sys.path.insert(0, str(REPO))
            from webcam.config import Args
            from webcam.vid2vid import Pipeline

            args = Args(
                host="0.0.0.0",
                port=7860,
                reload=False,
                mode="default",
                max_queue_size=4,
                timeout=0.0,
                safety_checker=False,
                taesd=True,
                ssl_certfile=None,
                ssl_keyfile=None,
                debug=False,
                acceleration="none",
                engine_dir="engines",
                config_path="./configs/prompts/personalive_online.yaml",
            )
            _pipeline = Pipeline(args, torch.device("cuda:0"))
    return _pipeline


@spaces.GPU(duration=120)
def render_smoke(reference: Image.Image, frame1: Image.Image, frame2: Image.Image,
                 frame3: Image.Image, frame4: Image.Image):
    if any(x is None for x in [reference, frame1, frame2, frame3, frame4]):
        raise gr.Error("Provide one reference image and four driving frames.")

    if not torch.cuda.is_available():
        raise gr.Error("ZeroGPU did not allocate CUDA.")

    pipe = get_pipeline()
    pipe.fuse_reference(reference.convert("RGB"))

    for image in [frame1, frame2, frame3, frame4]:
        params = pipe.InputParams()
        buf = io.BytesIO()
        image.convert("RGB").save(buf, format="JPEG", quality=90)
        from webcam.util import bytes_to_tensor
        params.image = bytes_to_tensor(buf.getvalue())
        pipe.accept_new_params(params)

    for _ in range(1200):
        generated = pipe.produce_outputs()
        if generated:
            return generated[0]

    raise gr.Error("PersonaLive produced no frame before the render timeout.")


def status():
    return (
        f"PersonaLive commit: {PERSONALIVE_COMMIT}\n"
        f"CUDA available: {torch.cuda.is_available()}\n"
        f"CUDA device: {torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'none'}"
    )


with gr.Blocks(title="Kémzy PersonaLive ZeroGPU") as demo:
    gr.Markdown("# Kémzy PersonaLive GPU")
    gr.Markdown(
        "Real CUDA smoke test for the Kémzy neural renderer. "
        "The test submits one reference image plus four driving frames."
    )
    with gr.Row():
        reference = gr.Image(type="pil", label="Reference")
        frame1 = gr.Image(type="pil", label="Driving 1")
        frame2 = gr.Image(type="pil", label="Driving 2")
        frame3 = gr.Image(type="pil", label="Driving 3")
        frame4 = gr.Image(type="pil", label="Driving 4")
    run = gr.Button("Run real GPU render", variant="primary")
    output = gr.Image(type="pil", label="Generated frame")
    info = gr.Textbox(value=status, label="Renderer status", interactive=False)
    run.click(
        render_smoke,
        inputs=[reference, frame1, frame2, frame3, frame4],
        outputs=output,
    )

demo.queue(max_size=4).launch()
