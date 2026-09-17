from __future__ import annotations

import os
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from PIL import Image

REPO_URL = "https://github.com/KlingAIResearch/LivePortrait.git"
LIVEPORTRAIT_COMMIT = os.getenv("LIVEPORTRAIT_COMMIT", "9b294b3d0536135442ea73cb01e6cb3ca7029dd3")
REPO_DIR = Path(os.getenv("LIVEPORTRAIT_REPO", "/opt/LivePortrait"))
WEIGHTS_DIR = Path(os.getenv("LIVEPORTRAIT_WEIGHTS", "/models/liveportrait"))
MODEL_REPO = os.getenv("LIVEPORTRAIT_MODEL_REPO", "KlingTeam/LivePortrait")

REQUIRED_CHECKPOINTS = (
    "liveportrait/base_models/appearance_feature_extractor.pth",
    "liveportrait/base_models/motion_extractor.pth",
    "liveportrait/base_models/warping_module.pth",
    "liveportrait/base_models/spade_generator.pth",
    "liveportrait/retargeting_models/stitching_retargeting_module.pth",
)


def build_motion_inputs(pose: list[float], expression: list[float]):
    if len(pose) != 3:
        raise ValueError("pose must contain 3 values")
    if len(expression) != 63:
        raise ValueError("expression must contain 63 values")
    pose_degrees = np.asarray(pose, dtype=np.float32).reshape(1, 3) * 25.0
    expression_tensor = np.asarray(expression, dtype=np.float32).reshape(1, 21, 3)
    return pose_degrees, expression_tensor


@dataclass
class SourceFeatures:
    handle: str
    feature_3d: torch.Tensor
    kp_info: dict[str, torch.Tensor]


class LivePortraitAdapter:
    """Kémzy's thin session/cache layer over the official LivePortrait engine."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._sources: dict[str, SourceFeatures] = {}
        self._wrapper = None
        self._ready = False
        self._error: str | None = None

    @property
    def ready(self) -> bool:
        return self._ready

    @property
    def error(self) -> str | None:
        return self._error

    def _ensure_weights(self) -> None:
        required = [WEIGHTS_DIR / path for path in REQUIRED_CHECKPOINTS]
        if all(path.is_file() and path.stat().st_size > 0 for path in required):
            return
        if os.getenv("KEMZY_ALLOW_MODEL_DOWNLOAD", "0") != "1":
            missing = [str(path) for path in required if not path.is_file()]
            raise RuntimeError(
                "LivePortrait checkpoints are missing and automatic model download is disabled: "
                + ", ".join(missing)
            )
        from huggingface_hub import snapshot_download
        snapshot_download(
            repo_id=MODEL_REPO,
            allow_patterns=["liveportrait/**"],
            local_dir=WEIGHTS_DIR,
        )
        missing = [str(path) for path in required if not path.is_file() or path.stat().st_size == 0]
        if missing:
            raise RuntimeError("LivePortrait model provisioning incomplete: " + ", ".join(missing))

    def load(self) -> None:
        with self._lock:
            if self._ready:
                return
            try:
                if not REPO_DIR.is_dir():
                    raise RuntimeError(f"Pinned LivePortrait tree is missing: {REPO_DIR}")
                if str(REPO_DIR) not in os.sys.path:
                    os.sys.path.insert(0, str(REPO_DIR))
                self._ensure_weights()
                from src.config.inference_config import InferenceConfig
                from src.live_portrait_wrapper import LivePortraitWrapper

                cfg = InferenceConfig(
                    checkpoint_F=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[0]),
                    checkpoint_M=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[1]),
                    checkpoint_W=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[2]),
                    checkpoint_G=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[3]),
                    checkpoint_S=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[4]),
                    flag_do_crop=False,
                    flag_do_rot=False,
                    flag_pasteback=False,
                    flag_do_torch_compile=os.getenv("KEMZY_TORCH_COMPILE", "0") == "1",
                    flag_use_half_precision=True,
                    flag_relative_motion=True,
                    animation_region="all",
                    flag_stitching=True,
                )
                self._wrapper = LivePortraitWrapper(cfg)
                if not str(self._wrapper.device).startswith("cuda"):
                    raise RuntimeError(f"Kémzy GPU renderer requires CUDA, got device={self._wrapper.device}")
                self._ready = True
                self._error = None
            except Exception as exc:
                self._ready = False
                self._error = f"{type(exc).__name__}: {exc}"
                raise

    def prepare_source(self, image: Image.Image) -> str:
        self.load()
        prepared = self._wrapper.prepare_source(np.asarray(image.convert("RGB"), dtype=np.uint8))
        with torch.no_grad():
            kp_info = self._wrapper.get_kp_info(prepared, flag_refine_info=True)
            feature = self._wrapper.extract_feature_3d(prepared)
        source = SourceFeatures(
            handle=uuid.uuid4().hex,
            feature_3d=feature.detach(),
            kp_info={k: v.detach() for k, v in kp_info.items() if isinstance(v, torch.Tensor)},
        )
        with self._lock:
            self._sources[source.handle] = source
            while len(self._sources) > 4:
                self._sources.pop(next(iter(self._sources)))
        return source.handle

    def render(self, handle: str, pose: list[float], expression: list[float]) -> Image.Image:
        self.load()
        pose_degrees, expression_np = build_motion_inputs(pose, expression)
        with self._lock:
            source = self._sources.get(handle)
        if source is None:
            raise KeyError("unknown source handle")

        device = self._wrapper.device
        source_kp = source.kp_info
        feature = source.feature_3d
        pitch = torch.tensor([[pose_degrees[0, 0]]], device=device)
        yaw = torch.tensor([[pose_degrees[0, 1]]], device=device)
        roll = torch.tensor([[pose_degrees[0, 2]]], device=device)
        expression_delta = torch.from_numpy(expression_np).to(device=device)

        from src.utils.camera import get_rotation_matrix

        R_s = get_rotation_matrix(source_kp["pitch"], source_kp["yaw"], source_kp["roll"])
        # Live camera packets are deltas around the neutral camera pose. This is
        # the same relative-motion construction used by the official pipeline:
        # R_new = (R_d_i @ R_d_0^T) @ R_s, with R_d_0 = identity for a calibrated neutral stream.
        R_d_i = get_rotation_matrix(pitch, yaw, roll)
        R_new = R_d_i @ R_s

        x_c_s = source_kp["kp"]
        delta_new = source_kp["exp"] + expression_delta
        scale_new = source_kp["scale"]
        t_new = source_kp["t"].clone()
        t_new[..., 2].fill_(0)
        x_d_new = scale_new * (x_c_s @ R_new + delta_new) + t_new

        x_s = self._wrapper.transform_keypoint(source_kp)
        x_d_new = self._wrapper.stitching(x_s, x_d_new)
        with torch.no_grad():
            out = self._wrapper.warp_decode(feature, x_s, x_d_new)
        return Image.fromarray(self._wrapper.parse_output(out["out"])[0])
