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

REPO_DIR = Path(os.getenv("LIVEPORTRAIT_REPO", "/tmp/LivePortrait"))
WEIGHTS_DIR = Path(os.getenv("LIVEPORTRAIT_WEIGHTS", str(REPO_DIR / "pretrained_weights")))
MODEL_REPO = os.getenv("LIVEPORTRAIT_MODEL_REPO", "KlingTeam/LivePortrait")

@dataclass
class SourceFeatures:
    handle: str
    feature_3d: torch.Tensor
    kp_info: dict[str, torch.Tensor]

class LivePortraitAdapter:
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
            subprocess.run([
                "git", "clone", "--depth", "1",
                "https://github.com/KlingAIResearch/LivePortrait.git",
                str(REPO_DIR),
            ], check=True)
        if str(REPO_DIR) not in sys.path:
            sys.path.insert(0, str(REPO_DIR))

    def _ensure_weights(self) -> None:
        from huggingface_hub import snapshot_download
        target = WEIGHTS_DIR / "liveportrait"
        required = [
            target / "base_models" / "appearance_feature_extractor.pth",
            target / "base_models" / "motion_extractor.pth",
            target / "base_models" / "warping_module.pth",
            target / "base_models" / "spade_generator.pth",
            target / "retargeting_models" / "stitching_retargeting_module.pth",
        ]
        if all(path.exists() for path in required):
            return
        snapshot_download(
            repo_id=MODEL_REPO,
            allow_patterns=["liveportrait/**"],
            local_dir=WEIGHTS_DIR,
        )

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
                    checkpoint_F=str(WEIGHTS_DIR / "liveportrait/base_models/appearance_feature_extractor.pth"),
                    checkpoint_M=str(WEIGHTS_DIR / "liveportrait/base_models/motion_extractor.pth"),
                    checkpoint_W=str(WEIGHTS_DIR / "liveportrait/base_models/warping_module.pth"),
                    checkpoint_G=str(WEIGHTS_DIR / "liveportrait/base_models/spade_generator.pth"),
                    checkpoint_S=str(WEIGHTS_DIR / "liveportrait/retargeting_models/stitching_retargeting_module.pth"),
                    flag_do_crop=False,
                    flag_do_rot=False,
                    flag_pasteback=False,
                    flag_do_torch_compile=False,
                    flag_use_half_precision=True,
                )
                self._wrapper = LivePortraitWrapper(cfg)
                self._ready = True
                self._error = None
            except Exception as exc:
                self._ready = False
                self._error = f"{type(exc).__name__}: {exc}"
                raise

    def prepare_source(self, image: Image.Image) -> str:
        self.load()
        image = image.convert("RGB").resize((256, 256))
        prepared = self._wrapper.prepare_source(np.asarray(image, dtype=np.uint8))
        with torch.no_grad():
            kp_info = self._wrapper.get_kp_info(prepared, flag_refine_info=True)
            feature = self._wrapper.extract_feature_3d(prepared)
        source = SourceFeatures(
            handle=uuid.uuid4().hex,
            feature_3d=feature.detach().cpu(),
            kp_info={key: value.detach().cpu() for key, value in kp_info.items() if isinstance(value, torch.Tensor)},
        )
        with self._lock:
            self._sources[source.handle] = source
            while len(self._sources) > 8:
                self._sources.pop(next(iter(self._sources)))
        return source.handle

    def render(self, handle: str, pose: list[float], expression: list[float]) -> Image.Image:
        self.load()
        if len(pose) != 3 or len(expression) != 63:
            raise ValueError("pose must contain 3 values and expression must contain 63 values")
        with self._lock:
            source = self._sources.get(handle)
        if source is None:
            raise KeyError("unknown source handle")

        device = self._wrapper.device
        source_kp = {key: value.to(device) for key, value in source.kp_info.items()}
        feature = source.feature_3d.to(device)
        source_rotation = self._rotation(source_kp["pitch"], source_kp["yaw"], source_kp["roll"])
        source_kp_base = source_kp["kp"]
        source_exp = source_kp["exp"]
        source_scale = source_kp["scale"]
        source_t = source_kp["t"]

        pitch, yaw, roll = [float(v) * 25.0 for v in pose]
        driving_rotation = self._rotation(
            torch.tensor([[pitch]], device=device),
            torch.tensor([[yaw]], device=device),
            torch.tensor([[roll]], device=device),
        )
        relative_rotation = driving_rotation @ source_rotation
        expression_delta = torch.tensor(expression, dtype=torch.float32, device=device).reshape(1, 21, 3) * 0.08
        x_s = source_scale * (source_kp_base @ source_rotation + source_exp) + source_t[:, None, :]
        x_d = source_scale * (source_kp_base @ relative_rotation + source_exp + expression_delta) + source_t[:, None, :]
        x_d = self._wrapper.stitching(x_s, x_d)
        out = self._wrapper.warp_decode(feature, x_s, x_d)
        return Image.fromarray(self._wrapper.parse_output(out["out"])[0])

    @staticmethod
    def _rotation(pitch: torch.Tensor, yaw: torch.Tensor, roll: torch.Tensor) -> torch.Tensor:
        from src.utils.camera import get_rotation_matrix
        return get_rotation_matrix(pitch, yaw, roll)
