from app import renderer_status, validate_motion

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
