from __future__ import annotations

import asyncio
import os
import time
import uuid
from dataclasses import dataclass
from typing import Any


@dataclass
class Worker:
    worker_id: str
    websocket: Any
    busy: bool = False
    last_seen: float = 0.0


class _ModalSocketAdapter:
    def __init__(self, websocket: Any):
        self.websocket = websocket

    async def send_json(self, payload: dict[str, Any]) -> None:
        import json
        await self.websocket.send(json.dumps(payload))


class GPUWorkerBroker:
    """Render-side broker for GPU workers.

    It supports the original outbound-worker protocol and, when
    MODAL_GPU_WS_URL is configured, also maintains a client connection to a
    Modal WebSocket GPU worker. This keeps the public Android API unchanged.
    """

    def __init__(self, secret: str, heartbeat_timeout: float = 45.0):
        self.secret = secret
        self.heartbeat_timeout = heartbeat_timeout
        self.workers: dict[str, Worker] = {}
        self.pending: dict[str, asyncio.Future] = {}
        self.lock = asyncio.Lock()
        self.modal_url = (os.getenv("MODAL_GPU_WS_URL") or "").strip()
        self.modal_task: asyncio.Task | None = None

    async def start(self) -> None:
        if self.modal_url and self.modal_task is None:
            self.modal_task = asyncio.create_task(self._modal_loop())

    async def stop(self) -> None:
        if self.modal_task:
            self.modal_task.cancel()
            try:
                await self.modal_task
            except asyncio.CancelledError:
                pass
            self.modal_task = None

    async def _modal_loop(self) -> None:
        import json
        import websockets

        while True:
            try:
                headers = {}
                if self.secret:
                    headers["X-Kemzy-Secret"] = self.secret

                async with websockets.connect(
                    self.modal_url,
                    extra_headers=headers,
                    ping_interval=20,
                    ping_timeout=10,
                    close_timeout=5,
                    max_size=50 * 1024 * 1024,
                ) as ws:
                    worker_id = f"modal-{uuid.uuid4().hex[:12]}"
                    adapter = _ModalSocketAdapter(ws)
                    await ws.send(json.dumps({
                        "type": "register",
                        "worker_id": worker_id,
                        "renderer": "FasterLivePortrait",
                        "backend": "FasterLivePortrait1",
                    }))

                    worker = await self.register(adapter, worker_id)
                    print("MODAL_GPU_WORKER_CONNECTED", worker_id, flush=True)

                    async def heartbeat():
                        while True:
                            await asyncio.sleep(15)
                            await self.heartbeat(worker_id)
                            await ws.send(json.dumps({"type": "heartbeat"}))

                    heartbeat_task = asyncio.create_task(heartbeat())
                    try:
                        async for raw in ws:
                            message = json.loads(raw)
                            kind = message.get("type")
                            if kind == "registered":
                                print("MODAL_GPU_WORKER_REGISTERED", flush=True)
                            elif kind == "heartbeat_ack":
                                await self.heartbeat(worker_id)
                            elif kind == "result":
                                await self.heartbeat(worker_id)
                                await self.complete(
                                    str(message.get("jobId", "")),
                                    message.get("result", {}),
                                )
                    finally:
                        heartbeat_task.cancel()
                        try:
                            await heartbeat_task
                        except asyncio.CancelledError:
                            pass
                        await self.unregister(worker_id, adapter)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                print("MODAL_GPU_WORKER_ERROR:", repr(exc), flush=True)
                await asyncio.sleep(5)

    async def register(self, websocket: Any, worker_id: str) -> Worker:
        async with self.lock:
            old = self.workers.get(worker_id)
            if old and old.websocket is not websocket:
                try:
                    await old.websocket.close(code=1012)
                except Exception:
                    pass
            worker = Worker(worker_id=worker_id, websocket=websocket, last_seen=time.time())
            self.workers[worker_id] = worker
            return worker

    async def unregister(self, worker_id: str, websocket: Any | None = None) -> None:
        async with self.lock:
            worker = self.workers.get(worker_id)
            if worker and (websocket is None or worker.websocket is websocket):
                self.workers.pop(worker_id, None)
                doomed = [job_id for job_id, fut in self.pending.items() if not fut.done()]
                for job_id in doomed:
                    fut = self.pending.pop(job_id, None)
                    if fut and not fut.done():
                        fut.set_exception(RuntimeError("GPU worker disconnected"))

    async def heartbeat(self, worker_id: str) -> None:
        worker = self.workers.get(worker_id)
        if worker:
            worker.last_seen = time.time()

    async def acquire_worker(self) -> Worker:
        async with self.lock:
            stale = [
                worker_id for worker_id, worker in self.workers.items()
                if time.time() - worker.last_seen > self.heartbeat_timeout
            ]
            for worker_id in stale:
                self.workers.pop(worker_id, None)

            for worker in self.workers.values():
                if not worker.busy:
                    worker.busy = True
                    worker.last_seen = time.time()
                    return worker

        raise RuntimeError("GPU_TEMPORARILY_UNAVAILABLE")

    async def release_worker(self, worker: Worker) -> None:
        async with self.lock:
            current = self.workers.get(worker.worker_id)
            if current is worker:
                worker.busy = False
                worker.last_seen = time.time()

    async def submit(self, action: str, data: dict[str, Any], timeout: float = 120.0) -> dict[str, Any]:
        worker = await self.acquire_worker()
        job_id = str(uuid.uuid4())
        future = asyncio.get_running_loop().create_future()
        self.pending[job_id] = future
        try:
            await worker.websocket.send_json({
                "type": "job",
                "jobId": job_id,
                "action": action,
                "data": data,
            })
            result = await asyncio.wait_for(future, timeout=timeout)
            if not isinstance(result, dict):
                raise RuntimeError("GPU worker returned an invalid result")
            return result
        finally:
            self.pending.pop(job_id, None)
            await self.release_worker(worker)

    async def complete(self, job_id: str, result: dict[str, Any]) -> None:
        future = self.pending.get(job_id)
        if future and not future.done():
            future.set_result(result)

    def snapshot(self) -> dict[str, Any]:
        return {
            "workers_connected": len(self.workers),
            "workers_busy": sum(1 for worker in self.workers.values() if worker.busy),
            "workers": [
                {
                    "worker_id": worker.worker_id,
                    "busy": worker.busy,
                    "last_seen_seconds": round(max(0.0, time.time() - worker.last_seen), 2),
                }
                for worker in self.workers.values()
            ],
        }
