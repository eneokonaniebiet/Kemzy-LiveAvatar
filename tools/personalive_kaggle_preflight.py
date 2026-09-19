"""Kaggle preflight for the PersonaLive GPU proof.

This intentionally does not import TensorFlow. PersonaLive's offline inference
path uses MediaPipe, PyTorch, Diffusers, Transformers, Decord, etc.; TensorFlow
is not required for the renderer import proof.
"""
from __future__ import annotations

import importlib
import os
import sys
from pathlib import Path


ROOT = Path("/kaggle/working")
REPO = ROOT / "Kemzy-LiveAvatar"

# Keep the repository itself importable.
for candidate in (
    REPO,
    REPO / "PersonaLive",
    REPO / "personalive",
    REPO / "tools",
):
    if candidate.exists():
        sys.path.insert(0, str(candidate))


def locate_inference_module() -> Path | None:
    candidates = []
    for base in (REPO, ROOT):
        if base.exists():
            candidates.extend(base.rglob("inference_offline.py"))
    return next(iter(candidates), None)


print("=== PersonaLive Kaggle preflight ===")

import torch
print(f"torch={torch.__version__}")
print(f"torch_cuda={torch.version.cuda}")
print(f"cuda_available={torch.cuda.is_available()}")
if not torch.cuda.is_available():
    raise RuntimeError("CUDA is not available; stop before model/inference work.")
print(f"gpu={torch.cuda.get_device_name(0)}")

import google.protobuf
print(f"protobuf={google.protobuf.__version__}")
print(f"protobuf_runtime_version={hasattr(google.protobuf, 'runtime_version')}")

import mediapipe as mp
print(f"mediapipe={getattr(mp, '__version__', 'unknown')}")

# The key regression guard: importing TensorFlow must not be part of this proof.
if "tensorflow" in sys.modules:
    raise RuntimeError(
        "TensorFlow is already loaded in this notebook. Restart the Kaggle "
        "session and run this preflight before any TensorFlow import."
    )

inference_file = locate_inference_module()
if inference_file is None:
    raise FileNotFoundError(
        "Could not locate inference_offline.py under /kaggle/working. "
        "Check the PersonaLive clone path before continuing."
    )

print(f"inference_offline={inference_file}")
module_dir = str(inference_file.parent)
if module_dir not in sys.path:
    sys.path.insert(0, module_dir)

module = importlib.import_module("inference_offline")
print(f"PersonaLive inference_offline import=PASS ({module.__file__})")
print("=== PRELIGHT PASS: GPU + MediaPipe + PersonaLive import ===")
