from __future__ import annotations

import os
import subprocess
import sys
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
from PIL import Image

REPO_URL = "https://github.com/KlingAIResearch/LivePortrait.git"
# Pin the complete upstream LivePortrait tree so Kémzy does not silently drift
# when the upstream repository changes.
LIVEPORTRAIT_COMMIT = os.getenv(
    "LIVEPORTRAIT_COMMIT",
    "9b294b3d0536135442ea73cb01e6cb3ca7029dd3",
)
REPO_DIR = Path(os.getenv("LIVEPORTRAIT_REPO", "/tmp/LivePortrait"))
WEIGHTS_DIR = Path(os.getenv("LIVEPORTRAIT_WEIGHTS", str(REPO_DIR / "pretrained_weights")))
MODEL_REPO = os.getenv("LIVEPORTRAIT_MODEL_REPO", "KlingTeam/LivePortrait")

REQUIRED_CHECKPOINTS = (
    "liveportrait/base_models/appearance_feature_extractor.pth",
    "liveportrait/base_models/motion_extractor.pth",
    "liveportrait/base_models/warping_module.pth",
    "liveportrait/base_models/spade_generator.pth",
    "liveportrait/retargeting_models/stitching_retargeting_module.pth",
)


@dataclass
class SourceFeatures:
    handle: str
    feature_3d: torch.Tensor
    kp_info: dict[str, torch.Tensor]


def build_motion_inputs(pose: list[float], expression: list[float]):
    """Convert Kémzy's compact driver payload to the upstream tensor shapes."""
    if len(pose) != 3:
        raise ValueError("pose must contain 3 values")
    if len(expression) != 63:
        raise ValueError("expression must contain 63 values")
    pose_degrees = np.asarray(pose, dtype=np.float32).reshape(1, 3) * 25.0
    expression_tensor = np.asarray(expression, dtype=np.float32).reshape(1, 21, 3)
    return pose_degrees, expression_tensor


class LivePortraitAdapter:
    """Kémzy bridge around the official KlingAIResearch LivePortrait tree."""

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

    def _ensure_code(self) -> None:
        if not REPO_DIR.exists():
            subprocess.run(
                ["git", "clone", "--depth", "1", REPO_URL, str(REPO_DIR)],
                check=True,
            )
        # Always put the exact upstream revision on disk. This is deliberately
        # deterministic rather than following whatever happens to be `main`.
        current = subprocess.run(
            ["git", "-C", str(REPO_DIR), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        if current != LIVEPORTRAIT_COMMIT:
            subprocess.run(
                ["git", "-C", str(REPO_DIR), "fetch", "--depth", "1", "origin", LIVEPORTRAIT_COMMIT],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(REPO_DIR), "checkout", "--detach", LIVEPORTRAIT_COMMIT],
                check=True,
            )
        if str(REPO_DIR) not in sys.path:
            sys.path.insert(0, str(REPO_DIR))

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
                self._ensure_code()
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
                    flag_do_torch_compile=False,
                    flag_use_half_precision=True,
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
        image = image.convert("RGB")
        prepared = self._wrapper.prepare_source(np.asarray(image, dtype=np.uint8))
        with torch.no_grad():
            kp_info = self._wrapper.get_kp_info(prepared, flag_refine_info=True)
            feature = self._wrapper.extract_feature_3d(prepared)

        source = SourceFeatures(
            handle=uuid.uuid4().hex,
            feature_3d=feature.detach().cpu(),
            kp_info={
                key: value.detach().cpu()
                for key, value in kp_info.items()
                if isinstance(value, torch.Tensor)
            },
        )
        with self._lock:
            self._sources[source.handle] = source
            while len(self._sources) > 8:
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
        source_kp = {key: value.to(device) for key, value in source.kp_info.items()}
        feature = source.feature_3d.to(device)

        # Match the official LivePortrait image-driven relative-motion path:
        # source rotation is the canonical orientation, while the Kémzy driver
        # is interpreted as a delta from the neutral driving orientation.
        source_rotation = self._rotation(source_kp["pitch"], source_kp["yaw"], source_kp["roll"])
        source_canonical = source_kp["kp"]
        source_exp = source_kp["exp"]
        source_scale = source_kp["scale"]
        source_t = source_kp["t"].clone()

        pitch, yaw, roll = [float(v) for v in pose_degrees[0]]
        driving_rotation = self._rotation(
            torch.tensor([[pitch]], dtype=torch.float32, device=device),
            torch.tensor([[yaw]], dtype=torch.float32, device=device),
            torch.tensor([[roll]], dtype=torch.float32, device=device),
        )
        relative_rotation = driving_rotation @ source_rotation
        expression_delta = torch.from_numpy(expression_np).to(device=device)

        # The equations below are the same keypoint construction used by the
        # upstream wrapper; the neural rendering itself is entirely upstream.
        x_s = self._wrapper.transform_keypoint(source_kp)
        x_d = source_scale * (source_canonical @ relative_rotation + source_exp + expression_delta)
        x_d[:, :, 0:2] += source_t[:, None, 0:2]

        x_d = self._wrapper.stitching(x_s, x_d)
        with torch.no_grad():
            out = self._wrapper.warp_decode(feature, x_s, x_d)
        return Image.fromarray(self._wrapper.parse_output(out["out"])[0])

    @staticmethod
    def _rotation(pitch: torch.Tensor, yaw: torch.Tensor, roll: torch.Tensor) -> torch.Tensor:
        from src.utils.camera import get_rotation_matrix

        return get_rotation_matrix(pitch, yaw, roll)
