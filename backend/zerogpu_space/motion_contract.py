from __future__ import annotations

import numpy as np


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
