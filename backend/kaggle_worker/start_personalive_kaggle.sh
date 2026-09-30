#!/usr/bin/env bash
set -euo pipefail

DATASET_ROOT="${KEMZY_DATASET_ROOT:-}"
if [[ -z "$DATASET_ROOT" ]]; then
  for candidate in /kaggle/input/notebooks/*/PersonaLive; do
    if [[ -d "$candidate" ]]; then DATASET_ROOT="$candidate"; break; fi
  done
fi
if [[ -n "$DATASET_ROOT" && -d "$DATASET_ROOT" ]]; then
  ROOT="$DATASET_ROOT"
else
  ROOT="${PERSONALIVE_ROOT:-/kaggle/working/PersonaLive}"
fi
export PERSONALIVE_ROOT="$ROOT"
cd "$ROOT"
echo "PersonaLive root: $ROOT"

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
import os
root=Path(os.environ["PERSONALIVE_ROOT"])/"pretrained_weights"
required=[
"personalive/denoising_unet.pth",
"personalive/motion_encoder.pth",
"personalive/motion_extractor.pth",
"personalive/pose_guider.pth",
"personalive/reference_unet.pth",
"personalive/temporal_module.pth",
]
missing=[p for p in required if not (root/p).is_file()]
print("Missing:", missing if missing else "NONE")
if missing: raise SystemExit("Missing PersonaLive weights: " + ", ".join(missing))
print("ALL 6 PERSONA LIVE WEIGHTS READY")
PY

echo
echo "=== START KEMZY OUTBOUND GPU WORKER ==="
WORKER="${KEMZY_WORKER_SCRIPT:-/kaggle/working/Kemzy-LiveAvatar/backend/kaggle_worker/personalive_outbound_worker.py}"
if [[ ! -f "$WORKER" ]]; then
  for candidate in /kaggle/input/notebooks/*/Kemzy-LiveAvatar/backend/kaggle_worker/personalive_outbound_worker.py; do
    if [[ -f "$candidate" ]]; then WORKER="$candidate"; break; fi
  done
fi
if [[ ! -f "$WORKER" ]]; then echo "ERROR: personalive_outbound_worker.py not found"; exit 3; fi
exec python "$WORKER"
