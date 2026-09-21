import base64

from fastapi.testclient import TestClient

from backend.app import main


class FakeRenderer:
    async def health(self):
        return type("Health", (), {"status": "ready", "backend": "test", "error": None})()

    async def prepare_source(self, data: bytes, content_type: str) -> str:
        return "source-test"

    async def render_frame(
        self,
        source_handle,
        pose,
        expression,
        landmarks,
        eye_ratio=None,
        lip_ratio=None,
        timestamp_ms=0,
    ):
        return {
            "status": "rendered",
            "mime_type": "image/png",
            "image_base64": base64.b64encode(b"png-bytes").decode("ascii"),
        }


def test_stream_returns_rendered_frame_for_valid_motion_packet(monkeypatch):
    monkeypatch.setattr(main, "_RENDERER", FakeRenderer())
    client = TestClient(main.app)

    session = client.post("/v1/sessions", json={"source_type": "image"})
    assert session.status_code == 200
    session_id = session.json()["session_id"]
    main._SESSION_HANDLES[session_id] = "source-test"

    with client.websocket_connect(f"/v1/stream/{session_id}") as socket:
        socket.send_json({
            "timestamp_ms": 1234,
            "pose": [0.1, -0.2, 0.05],
            "expression": [0.0] * 63,
            "landmarks": [],
        })
        result = socket.receive_json()

    assert result["type"] == "frame"
    assert result["timestamp_ms"] == 1234
    assert result["mime_type"] == "image/png"
    assert result["frame_base64"] == base64.b64encode(b"png-bytes").decode("ascii")


def test_stream_rejects_unknown_session(monkeypatch):
    monkeypatch.setattr(main, "_RENDERER", FakeRenderer())
    client = TestClient(main.app)

    with client.websocket_connect("/v1/stream/unknown") as socket:
        result = socket.receive_json()

    assert result["type"] == "error"
    assert result["code"] == "source_not_ready"
