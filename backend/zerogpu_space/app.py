from __future__ import annotations

import os
from typing import Any

import gradio as gr
from PIL import Image

from liveportrait_adapter import LivePortraitAdapter

try:
    import spaces
    GPU = spaces.GPU
except ImportError:
    def GPU(*args: Any, **kwargs: Any):
        def decorator(fn):
            return fn
        return decorator

adapter = LivePortraitAdapter()
if os.getenv("KEMZY_SKIP_MODEL_LOAD", "0") != "1":
    try:
        adapter.load()
    except Exception:
        pass


def validate_motion(pose: list[float], expression: list[float], landmarks: list[float]):
    if len(pose) != 3:
        raise ValueError("pose must contain pitch, yaw, roll")
    if expression and len(expression) != 63:
        raise ValueError("expression must contain 63 values")
    if landmarks and len(landmarks) % 3 != 0:
        raise ValueError("landmarks must contain x,y,z triplets")
    return pose, expression or [0.0] * 63, landmarks


def renderer_status(loaded: bool) -> str:
    return "ready" if loaded else "degraded"


def health() -> dict[str, Any]:
    return {
        "status": renderer_status(adapter.ready),
        "service": "kemzy-zerogpu-renderer",
        "backend": "liveportrait-pytorch",
        "error": adapter.error,
    }


@GPU(duration=60)
def prepare_source(source: Image.Image) -> str:
    if source is None:
        raise ValueError("source image is required")
    return adapter.prepare_source(source)


@GPU(duration=30)
def render_motion(source_handle: str, pose: list[float], expression: list[float], landmarks: list[float]) -> Image.Image:
    if not source_handle:
        raise ValueError("source_handle is required")
    pose, expression, _ = validate_motion(pose, expression, landmarks)
    if not adapter.ready:
        raise RuntimeError(adapter.error or "neural renderer is not ready")
    return adapter.render(source_handle, pose, expression)


def status_text() -> str:
    return str(health())


with gr.Blocks(title="Kémzy Neural Renderer") as demo:
    gr.Markdown("# Kémzy Neural Renderer")
    gr.Markdown("Provider-neutral ZeroGPU adapter. It returns no synthetic frames when the neural model is unavailable.")
    source = gr.Image(type="pil", label="Face source (crop to a single face)")
    prepare = gr.Button("Prepare source")
    source_handle = gr.Textbox(label="Source handle")
    pose = gr.JSON(value=[0.0, 0.0, 0.0], label="Pose [pitch, yaw, roll]")
    expression = gr.JSON(value=[0.0] * 63, label="Expression 63 values")
    landmarks = gr.JSON(value=[], label="Landmarks")
    render = gr.Button("Render frame")
    output = gr.Image(type="pil", label="Rendered frame")
    status = gr.Textbox(label="Status")

    prepare.click(prepare_source, inputs=source, outputs=source_handle, api_name="prepare_source")
    render.click(render_motion, inputs=[source_handle, pose, expression, landmarks], outputs=output, api_name="render_motion")
    demo.load(status_text, outputs=status, api_name="health")

if __name__ == "__main__":
    demo.launch(server_name="0.0.0.0", server_port=int(os.getenv("PORT", "7860")))
