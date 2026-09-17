import numpy as np
import pytest

from backend.zerogpu_space.liveportrait_adapter import build_motion_inputs, normalize_driver_ratio


def test_build_motion_inputs_returns_official_keypoint_shapes():
    pose, expression = build_motion_inputs([0.1, -0.2, 0.05], [0.0] * 63)
    assert pose.shape == (1, 3)
    assert expression.shape == (1, 21, 3)
    assert pose.dtype == np.float32
    assert expression.dtype == np.float32


def test_build_motion_inputs_rejects_wrong_expression_size():
    with pytest.raises(ValueError, match="63"):
        build_motion_inputs([0.0, 0.0, 0.0], [0.0] * 62)


def test_normalize_driver_ratio_clamps_camera_values():
    assert normalize_driver_ratio(-1.0) == 0.0
    assert normalize_driver_ratio(0.25) == 0.25
    assert normalize_driver_ratio(2.0) == 1.0
