from __future__ import annotations

import asyncio
import base64
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from gradio_client import Client, handle_file

@dataclass(frozen=True)
class RendererHealth:
    status: str
    backend: str | None = None
    error: str | None = None

class RendererClient:
    def __init__(self, base_url: str, token: str = ''):
        self.base_url = base_url.rstrip('/')
        self.token = token

    def _client(self) -> Client:
        kwargs: dict[str, Any] = {'verbose': False}
        if self.token:
            kwargs['token'] = self.token
        return Client(self.base_url, **kwargs)

    async def health(self) -> RendererHealth:
        try:
            result = await asyncio.to_thread(self._client().predict, api_name='/health')
            data = result if isinstance(result, dict) else {'status': str(result)}
            return RendererHealth(status=str(data.get('status', 'degraded')), backend=data.get('backend'), error=data.get('error'))
        except Exception as exc:
            return RendererHealth(status='degraded', error=f'{type(exc).__name__}: {exc}')

    async def prepare_source(self, data: bytes, content_type: str) -> str:
        suffix = '.jpg' if content_type == 'image/jpeg' else '.png' if content_type == 'image/png' else '.webp'
        fd, path = tempfile.mkstemp(suffix=suffix)
        os.close(fd)
        try:
            Path(path).write_bytes(data)
            result = await asyncio.to_thread(self._client().predict, handle_file(path), api_name='/prepare_source')
            if not isinstance(result, str) or not result:
                raise RuntimeError('renderer returned an invalid source handle')
            return result
        finally:
            try:
                Path(path).unlink(missing_ok=True)
            except OSError:
                pass

    async def render_frame(self, source_handle: str, pose: list[float], expression: list[float], landmarks: list[float]) -> dict[str, Any]:
        result = await asyncio.to_thread(
            self._client().predict,
            source_handle,
            pose,
            expression,
            landmarks,
            api_name='/render_motion',
        )
        path = Path(result)
        if not path.exists():
            raise RuntimeError('renderer returned no frame file')
        encoded = base64.b64encode(path.read_bytes()).decode('ascii')
        return {'status': 'rendered', 'mime_type': 'image/png', 'image_base64': encoded}
