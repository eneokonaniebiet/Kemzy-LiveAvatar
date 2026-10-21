import base64

from fastapi.testclient import TestClient

from backend.app import main


class FakeBroker:
    def __init__(self):
        self.jobs = []

    async def start(self):
        return None

    async def stop(self):
        return None

    def snapshot(self):
        return {
            "workers_connected": 1,
            "workers_busy": 0,
            "workers": [{"worker_id": "test-worker", "busy": False, "last_seen_seconds": 0}],
        }

    async def submit(self, action, data, timeout=120.0):
        self.jobs.append((action, data))
        if action == "CREATE_SESSION":
            return {"status": "ready", "worker_id": "test-worker", "session_id": data["session_id"]}
        if action == "PREPARE_SOURCE":
            return {"status": "ready", "worker_id": "test-worker", "session_id": data["session_id"]}
        if action == "RENDER_FRAME":
            assert data["driving_images"], "live camera JPEG must reach the GPU worker"
            return {
                "status": "rendered",
                "worker_id": "test-worker",
                "mime_type": "image/jpeg",
                "image_base64": base64.b64encode(b"rendered-jpeg").decode("ascii"),
            }
        raise AssertionError(f"Unexpected broker action: {action}")


def setup_client(monkeypatch):
    broker = FakeBroker()
    monkeypatch.setattr(main, "BROKER", broker)
    main._SESSION_WORKERS.clear()
    return TestClient(main.app), broker


def test_stream_returns_rendered_frame_for_valid_driver_packet(monkeypatch):
    client, broker = setup_client(monkeypatch)
    with client:
        session = client.post("/v1/sessions", json={"source_type": "image"})
        assert session.status_code == 200
        session_id = session.json()["session_id"]

        source = client.post(
            f"/v1/sessions/{session_id}/source",
            files={"file": ("source.jpg", b"source-image", "image/jpeg")},
        )
        assert source.status_code == 200
        assert source.json()["status"] == "source_ready"

        camera_jpeg = base64.b64encode(b"camera-jpeg").decode("ascii")
        with client.websocket_connect(f"/v1/stream/{session_id}") as socket:
            socket.send_json({
                "type": "driver",
                "timestamp_ms": 1234,
                "yaw": 0.1,
                "pitch": -0.2,
                "roll": 0.05,
                "eye_left": 0.8,
                "eye_right": 0.9,
                "mouth_open": 0.3,
                "smile": 0.2,
                "driving_images": [camera_jpeg],
            })
            result = socket.receive_json()

        assert result["type"] == "frame"
        assert result["timestamp_ms"] == 1234
        assert result["mime_type"] == "image/jpeg"
        assert result["frame_base64"] == base64.b64encode(b"rendered-jpeg").decode("ascii")
        render_jobs = [job for job in broker.jobs if job[0] == "RENDER_FRAME"]
        assert len(render_jobs) == 1
        assert render_jobs[0][1]["driving_images"] == [camera_jpeg]


def test_stream_rejects_unknown_session(monkeypatch):
    client, _ = setup_client(monkeypatch)
    with client:
        with client.websocket_connect("/v1/stream/unknown") as socket:
            result = socket.receive_json()
        assert result["type"] == "error"
        assert result["code"] == "source_not_ready"
