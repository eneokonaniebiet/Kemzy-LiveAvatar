#!/usr/bin/env bash
set -euo pipefail

ROOT="${PERSONALIVE_ROOT:-/kaggle/working/PersonaLive}"
cd "$ROOT"

echo "=== KEMZY PERSONA LIVE KAGGLE BOOTSTRAP ==="
python - <<'PY'
import os, sys, torch
print("Python:", sys.version.split()[0])
print("Torch:", torch.__version__)
print("CUDA:", torch.cuda.is_available())
if torch.cuda.is_available():
    print("GPU:", torch.cuda.get_device_name(0))
PY

# PersonaLive itself does not require TensorFlow. Kaggle's preinstalled
# TensorFlow can be pulled in by MediaPipe's optional docs dependency and
# can conflict with the protobuf API expected by that TensorFlow build.
# Remove only TensorFlow packages; do not touch the PersonaLive weights.
python -m pip uninstall -y tensorflow tensorflow-cpu tensorflow-gpu tf-keras >/dev/null 2>&1 || true

python -m pip install -q --no-deps "mediapipe==0.10.13"
python -m pip install -q "protobuf==4.25.8"

python - <<'PY'
import google.protobuf
print("protobuf:", google.protobuf.__version__)
import mediapipe as mp
print("MediaPipe:", mp.__version__)
print("MediaPipe import: OK")
PY

echo
echo "=== PERSONA LIVE WEIGHTS ==="
python - <<'PY'
from pathlib import Path
root=Path("/kaggle/working/PersonaLive/pretrained_weights")
required=[
"personalive/denoising_unet.pth",
"personalive/motion_encoder.pth",
"personalive/motion_extractor.pth",
"personalive/pose_guider.pth",
"personalive/reference_unet.pth",
"personalive/temporal_module.pth",
"sd-vae-ft-mse/diffusion_pytorch_model.bin",
"sd-vae-ft-mse/config.json",
"sd-image-variations-diffusers/image_encoder/pytorch_model.bin",
"sd-image-variations-diffusers/image_encoder/config.json",
"sd-image-variations-diffusers/unet/diffusion_pytorch_model.bin",
"sd-image-variations-diffusers/unet/config.json",
"sd-image-variations-diffusers/model_index.json",
]
missing=[p for p in required if not (root/p).is_file()]
print("Missing:", missing if missing else "NONE")
if missing: raise SystemExit(2)
PY

echo
echo "=== START KEMZY OUTBOUND GPU WORKER ==="
exec python /kaggle/working/Kemzy-LiveAvatar/backend/kaggle_worker/personalive_outbound_worker.py
