import base64

from fastapi.testclient import TestClient

from backend.app import main


class FakeBroker:
    def __init__(self):
        self.sessions = set()

    def snapshot(self):
        return {
            "workers_connected": 1,
            "workers_busy": 0,
            "workers": [],
        }

    async def submit(self, action, data, timeout=120.0):
        if action == "CREATE_SESSION":
            self.sessions.add(data["session_id"])
            return {
                "status": "ready",
                "worker_id": "test-worker",
                "session_id": data["session_id"],
            }
        if action == "PREPARE_SOURCE":
            assert data["session_id"] in self.sessions
            return {
                "status": "ready",
                "worker_id": "test-worker",
                "session_id": data["session_id"],
            }
        if action == "RENDER_FRAME":
            assert data["session_id"] in self.sessions
            assert data["driving_images"]
            return {
                "status": "rendered",
                "mime_type": "image/png",
                "image_base64": base64.b64encode(b"png-bytes").decode("ascii"),
            }
        raise AssertionError(f"unexpected action: {action}")


def test_stream_returns_rendered_frame_for_valid_motion_packet(monkeypatch):
    broker = FakeBroker()
    monkeypatch.setattr(main, "BROKER", broker)
    monkeypatch.setattr(main, "_SESSION_WORKERS", {})

    client = TestClient(main.app)

    session = client.post("/v1/sessions", json={"source_type": "image"})
    assert session.status_code == 200
    session_id = session.json()["session_id"]

    source = client.post(
        f"/v1/sessions/{session_id}/source",
        files={"file": ("source.jpg", b"jpeg-bytes", "image/jpeg")},
    )
    assert source.status_code == 200

    with client.websocket_connect(f"/v1/stream/{session_id}") as socket:
        socket.send_json({
            "type": "driver",
            "timestamp_ms": 1234,
            "yaw": 0.1,
            "pitch": -0.2,
            "roll": 0.05,
            "eye_left": 0.9,
            "eye_right": 0.8,
            "mouth_open": 0.2,
            "smile": 0.3,
            "driving_images": [base64.b64encode(b"camera-jpeg").decode("ascii")],
        })
        result = socket.receive_json()

    assert result["type"] == "frame"
    assert result["timestamp_ms"] == 1234
    assert result["mime_type"] == "image/png"
    assert result["frame_base64"] == base64.b64encode(b"png-bytes").decode("ascii")


def test_stream_rejects_unknown_session(monkeypatch):
    broker = FakeBroker()
    monkeypatch.setattr(main, "BROKER", broker)
    monkeypatch.setattr(main, "_SESSION_WORKERS", {})

    client = TestClient(main.app)

    with client.websocket_connect("/v1/stream/unknown") as socket:
        result = socket.receive_json()

    assert result["type"] == "error"
    assert result["code"] == "source_not_ready"
