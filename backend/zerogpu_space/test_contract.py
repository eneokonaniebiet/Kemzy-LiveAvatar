from app import renderer_status, validate_motion
from liveportrait_adapter import LIVEPORTRAIT_COMMIT, REPO_URL, build_motion_inputs


def test_motion_requires_three_pose_values():
    try:
        validate_motion([0.0, 0.0], [0.0] * 63, [])
    except ValueError:
        return
    raise AssertionError("expected pose validation failure")


def test_motion_accepts_pose_and_expression():
    pose, expression, _ = validate_motion([0.1, -0.2, 0.3], [0.0] * 63, [])
    assert pose == [0.1, -0.2, 0.3]
    assert len(expression) == 63


def test_unloaded_renderer_is_degraded():
    assert renderer_status(False) == "degraded"


def test_build_motion_inputs_uses_upstream_pose_scale_and_expression_shape():
    pose, expression = build_motion_inputs([0.1, -0.2, 0.3], [0.0] * 63)
    assert pose.shape == (1, 3)
    assert expression.shape == (1, 21, 3)
    assert pose.tolist() == [[2.5, -5.0, 7.5]]


def test_liveportrait_engine_is_pinned_to_known_upstream_revision():
    assert REPO_URL == "https://github.com/KlingAIResearch/LivePortrait.git"
    assert LIVEPORTRAIT_COMMIT == "9b294b3d0536135442ea73cb01e6cb3ca7029dd3"
