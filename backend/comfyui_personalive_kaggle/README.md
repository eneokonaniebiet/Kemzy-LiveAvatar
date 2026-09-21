# Kémzy ComfyUI + PersonaLive Kaggle GPU validation

This directory is an isolated GPU proof path. It does **not** replace the existing PersonaLive renderer or APK.

## Goal

Run the current ComfyUI-PersonaLive custom node on a Kaggle NVIDIA T4/T4x2 notebook, execute a real 4-frame PersonaLive workflow, and measure:

- model-load time
- first inference latency
- warm 4-frame inference latency
- output frame count
- peak CUDA memory when available
- repeated-run stability

Kaggle currently lists T4x2 as an available notebook accelerator, with two 16 GB GPUs. citeturn0search0

The test follows ComfyUI's documented `/prompt` + WebSocket execution pattern rather than scraping the UI. citeturn1search0

## Run in Kaggle

Create a new Kaggle Notebook, select **GPU / T4x2**, enable Internet, then run:

```python
!git clone --depth 1 --branch feature/comfyui-personalive-kaggle-test https://github.com/eneokonaniebiet/Kemzy-LiveAvatar.git
%cd Kemzy-LiveAvatar
!python backend/comfyui_personalive_kaggle/kaggle_benchmark.py
```

The script installs ComfyUI and the PersonaLive custom node in an isolated directory under `/kaggle/working`. It does not modify the existing backend.

## Important

The PersonaLive custom node reports that its model set is roughly 15–20 GB and can download from Hugging Face on first use. If the notebook storage quota is insufficient, put the already-validated PersonaLive model directory in the notebook/dataset storage and set:

```bash
export KEMZY_PERSONALIVE_MODELS=/kaggle/input/<dataset>/persona_live
```

The benchmark is deliberately a **local GPU test first**. No Cloudflare, Render, APK, or temporary public GPU URL is changed until this test passes.

## Pass criteria for the next integration step

A successful benchmark must show:

1. PersonaLive custom node imports without errors.
2. CUDA is active.
3. PersonaLive weights load.
4. A real 4-frame workflow completes.
5. At least one output frame is returned.
6. Three additional warm runs complete without model reload.
7. No CUDA OOM.
8. Latency and VRAM are recorded.

Passing these proves the ComfyUI execution path works. It does **not** by itself prove 15/30 FPS live streaming; that requires the later persistent-session/network test.
