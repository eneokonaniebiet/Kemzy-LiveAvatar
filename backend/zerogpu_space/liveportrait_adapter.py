from __future__ import annotations

import os
import subprocess
import sys
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image

REPO_URL = "https://github.com/KlingAIResearch/LivePortrait.git"
LIVEPORTRAIT_COMMIT = os.getenv("LIVEPORTRAIT_COMMIT", "9b294b3d0536135442ea73cb01e6cb3ca7029dd3")
REPO_DIR = Path(os.getenv("LIVEPORTRAIT_REPO", "/tmp/LivePortrait"))
WEIGHTS_DIR = Path(os.getenv("LIVEPORTRAIT_WEIGHTS", str(REPO_DIR / "pretrained_weights")))
MODEL_REPO = os.getenv("LIVEPORTRAIT_MODEL_REPO", "KlingTeam/LivePortrait")
VIDEO_SOURCE_FPS = 15
VIDEO_SOURCE_MAX_SECONDS = 60

REQUIRED_CHECKPOINTS = (
    "liveportrait/base_models/appearance_feature_extractor.pth",
    "liveportrait/base_models/motion_extractor.pth",
    "liveportrait/base_models/warping_module.pth",
    "liveportrait/base_models/spade_generator.pth",
    "liveportrait/retargeting_models/stitching_retargeting_module.pth",
    "liveportrait/landmark.onnx",
    "insightface/models/buffalo_l/2d106det.onnx",
    "insightface/models/buffalo_l/det_10g.onnx",
)


def normalize_driver_ratio(value: float) -> float:
    return float(np.clip(float(value), 0.0, 1.0))


def build_motion_inputs(pose: list[float], expression: list[float]):
    if len(pose) != 3:
        raise ValueError("pose must contain 3 values")
    if len(expression) != 63:
        raise ValueError("expression must contain 63 values")
    pose_degrees = np.asarray(pose, dtype=np.float32).reshape(1, 3) * 30.0
    expression_tensor = np.asarray(expression, dtype=np.float32).reshape(1, 21, 3)
    return pose_degrees, expression_tensor


@dataclass
class SourceFeatures:
    handle: str
    feature_3d: torch.Tensor
    kp_info: dict[str, torch.Tensor]
    source_lmk: np.ndarray
    neutral_pose: np.ndarray | None = None
    neutral_expression: np.ndarray | None = None


@dataclass
class VideoSourceFeatures:
    handle: str
    frames: list[SourceFeatures]
    fps: int
    neutral_pose: np.ndarray | None = None
    neutral_expression: np.ndarray | None = None


class LivePortraitAdapter:
    """Kémzy bridge around the pinned official KlingAIResearch LivePortrait tree."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._sources: dict[str, SourceFeatures | VideoSourceFeatures] = {}
        self._wrapper = None
        self._cropper = None
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
            subprocess.run(["git", "clone", "--depth", "1", REPO_URL, str(REPO_DIR)], check=True)
        current = subprocess.run(["git", "-C", str(REPO_DIR), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
        if current != LIVEPORTRAIT_COMMIT:
            subprocess.run(["git", "-C", str(REPO_DIR), "fetch", "--depth", "1", "origin", LIVEPORTRAIT_COMMIT], check=True)
            subprocess.run(["git", "-C", str(REPO_DIR), "checkout", "--detach", LIVEPORTRAIT_COMMIT], check=True)
        if str(REPO_DIR) not in sys.path:
            sys.path.insert(0, str(REPO_DIR))

    def _ensure_weights(self) -> None:
        required = [WEIGHTS_DIR / path for path in REQUIRED_CHECKPOINTS]
        if all(path.is_file() and path.stat().st_size > 0 for path in required):
            return
        if os.getenv("KEMZY_ALLOW_MODEL_DOWNLOAD", "0") != "1":
            missing = [str(path) for path in required if not path.is_file()]
            raise RuntimeError("LivePortrait checkpoints are missing and automatic model download is disabled: " + ", ".join(missing))
        from huggingface_hub import snapshot_download
        snapshot_download(repo_id=MODEL_REPO, allow_patterns=["liveportrait/**", "insightface/**"], local_dir=WEIGHTS_DIR)
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
                from src.config.crop_config import CropConfig
                from src.config.inference_config import InferenceConfig
                from src.live_portrait_wrapper import LivePortraitWrapper
                from src.utils.cropper import Cropper
                cfg = InferenceConfig(
                    checkpoint_F=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[0]),
                    checkpoint_M=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[1]),
                    checkpoint_W=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[2]),
                    checkpoint_G=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[3]),
                    checkpoint_S=str(WEIGHTS_DIR / REQUIRED_CHECKPOINTS[4]),
                    flag_do_crop=True,
                    flag_do_rot=True,
                    flag_pasteback=False,
                    flag_do_torch_compile=False,
                    flag_use_half_precision=True,
                    flag_eye_retargeting=True,
                    flag_lip_retargeting=True,
                    flag_stitching=True,
                    flag_relative_motion=True,
                )
                crop_cfg = CropConfig(
                    insightface_root=str(WEIGHTS_DIR / "insightface"),
                    landmark_ckpt_path=str(WEIGHTS_DIR / "liveportrait/landmark.onnx"),
                    device_id=cfg.device_id,
                    flag_force_cpu=False,
                )
                self._wrapper = LivePortraitWrapper(cfg)
                if not str(self._wrapper.device).startswith("cuda"):
                    raise RuntimeError(f"Kémzy GPU renderer requires CUDA, got device={self._wrapper.device}")
                self._cropper = Cropper(crop_cfg=crop_cfg)
                self._ready = True
                self._error = None
            except Exception as exc:
                self._ready = False
                self._error = f"{type(exc).__name__}: {exc}"
                raise

    def _prepare_source_frame(self, image_rgb: np.ndarray) -> SourceFeatures:
        crop_info = self._cropper.crop_source_image(image_rgb, self._cropper.crop_cfg)
        if crop_info is None:
            raise ValueError("No face detected in the source frame")
        prepared = self._wrapper.prepare_source(crop_info["img_crop_256x256"])
        with torch.no_grad():
            kp_info = self._wrapper.get_kp_info(prepared, flag_refine_info=True)
            feature = self._wrapper.extract_feature_3d(prepared)
        return SourceFeatures(
            handle=uuid.uuid4().hex,
            feature_3d=feature.detach().cpu(),
            kp_info={key: value.detach().cpu() for key, value in kp_info.items() if isinstance(value, torch.Tensor)},
            source_lmk=np.asarray(crop_info["lmk_crop"], dtype=np.float32),
        )

    def prepare_source(self, image: Image.Image) -> str:
        self.load()
        source = self._prepare_source_frame(np.asarray(image.convert("RGB"), dtype=np.uint8))
        with self._lock:
            self._sources[source.handle] = source
            while len(self._sources) > 8:
                self._sources.pop(next(iter(self._sources)))
        return source.handle

    def prepare_source_video(self, video_path: str) -> str:
        self.load()
        cap = cv2.VideoCapture(video_path)
        if not cap.isOpened():
            raise ValueError("Unable to open source video")
        input_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        duration = frame_count / input_fps if frame_count else 0.0
        if duration > VIDEO_SOURCE_MAX_SECONDS:
            cap.release()
            raise ValueError(f"Source video is longer than {VIDEO_SOURCE_MAX_SECONDS} seconds")
        step = max(1, int(round(input_fps / VIDEO_SOURCE_FPS)))
        frames: list[np.ndarray] = []
        index = 0
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if index % step == 0:
                frames.append(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            index += 1
        cap.release()
        if not frames:
            raise ValueError("Source video contains no readable frames")
        frames = frames[: VIDEO_SOURCE_FPS * VIDEO_SOURCE_MAX_SECONDS]

        cropped = self._cropper.crop_source_video(frames, self._cropper.crop_cfg)
        if not cropped["frame_crop_lst"]:
            raise ValueError("No face detected in source video")
        source_frames: list[SourceFeatures] = []
        for frame, lmk in zip(cropped["frame_crop_lst"], cropped["lmk_crop_lst"]):
            prepared = self._wrapper.prepare_source(frame)
            with torch.no_grad():
                kp_info = self._wrapper.get_kp_info(prepared, flag_refine_info=True)
                feature = self._wrapper.extract_feature_3d(prepared)
            source_frames.append(SourceFeatures(
                handle=uuid.uuid4().hex,
                feature_3d=feature.detach().cpu(),
                kp_info={key: value.detach().cpu() for key, value in kp_info.items() if isinstance(value, torch.Tensor)},
                source_lmk=np.asarray(lmk, dtype=np.float32),
            ))
        if not source_frames:
            raise ValueError("No usable face frames were found in source video")
        handle = uuid.uuid4().hex
        video_source = VideoSourceFeatures(handle=handle, frames=source_frames, fps=VIDEO_SOURCE_FPS)
        with self._lock:
            self._sources[handle] = video_source
            while len(self._sources) > 4:
                self._sources.pop(next(iter(self._sources)))
        return handle

    def render(self, handle: str, pose: list[float], expression: list[float], eye_ratio: float | None = None, lip_ratio: float | None = None, timestamp_ms: int = 0) -> Image.Image:
        self.load()
        pose_degrees, expression_np = build_motion_inputs(pose, expression)
        with self._lock:
            root_source = self._sources.get(handle)
        if root_source is None:
            raise KeyError("unknown source handle")
        if isinstance(root_source, VideoSourceFeatures):
            index = int(max(0, timestamp_ms) / 1000.0 * root_source.fps) % len(root_source.frames)
            source = root_source.frames[index]
            if root_source.neutral_pose is None:
                root_source.neutral_pose = pose_degrees.reshape(3).copy()
                root_source.neutral_expression = expression_np.reshape(21, 3).copy()
            neutral_pose = root_source.neutral_pose.copy()
            neutral_expression = root_source.neutral_expression.copy()
        else:
            source = root_source
            if source.neutral_pose is None:
                source.neutral_pose = pose_degrees.reshape(3).copy()
                source.neutral_expression = expression_np.reshape(21, 3).copy()
            neutral_pose = source.neutral_pose.copy()
            neutral_expression = source.neutral_expression.copy()

        device = self._wrapper.device
        source_info = {key: value.to(device) for key, value in source.kp_info.items()}
        feature = source.feature_3d.to(device)
        source_canonical = source_info["kp"]
        x_s = self._wrapper.transform_keypoint(source_info)
        source_scale = source_info["scale"]
        source_translation = source_info["t"].clone()
        source_translation[..., 2].fill_(0)

        def make_driver(pose_values: np.ndarray, exp_values: np.ndarray) -> torch.Tensor:
            pose_tensor = torch.from_numpy(pose_values.reshape(1, 3)).to(device=device, dtype=source_info["kp"].dtype)
            rotation = self._rotation(pose_tensor[:, 0:1], pose_tensor[:, 1:2], pose_tensor[:, 2:3])
            exp_tensor = torch.from_numpy(exp_values.reshape(1, 21, 3)).to(device=device, dtype=source_info["exp"].dtype)
            return source_scale * (source_canonical @ rotation + exp_tensor) + source_translation

        x_d_current = make_driver(pose_degrees, expression_np)
        x_d_neutral = make_driver(neutral_pose, neutral_expression)
        x_d_new = x_s + (x_d_current - x_d_neutral)

        if eye_ratio is not None:
            combined_eye = self._wrapper.calc_combined_eye_ratio([[normalize_driver_ratio(eye_ratio)]], source.source_lmk)
            x_d_new = x_d_new + self._wrapper.retarget_eye(x_s, combined_eye)
        if lip_ratio is not None:
            combined_lip = self._wrapper.calc_combined_lip_ratio([normalize_driver_ratio(lip_ratio)], source.source_lmk)
            x_d_new = x_d_new + self._wrapper.retarget_lip(x_s, combined_lip)

        x_d_new = self._wrapper.stitching(x_s, x_d_new)
        with torch.no_grad():
            out = self._wrapper.warp_decode(feature, x_s, x_d_new)
        return Image.fromarray(self._wrapper.parse_output(out["out"])[0])

    @staticmethod
    def _rotation(pitch: torch.Tensor, yaw: torch.Tensor, roll: torch.Tensor) -> torch.Tensor:
        from src.utils.camera import get_rotation_matrix
        return get_rotation_matrix(pitch, yaw, roll)
