from __future__ import annotations

import base64
from dataclasses import dataclass
from typing import Any

import httpx


@dataclass(frozen=True)
class RendererHealth:
    status: str
    backend: str | None = None
    error: str | None = None


class RendererClient:
    """HTTP client for the persistent Kémzy GPU renderer."""

    def __init__(self, base_url: str, token: str = ''):
        self.base_url = base_url.rstrip('/')
        self.token = token

    def _headers(self) -> dict[str, str]:
        return {'Authorization': f'Bearer {self.token}'} if self.token else {}

    async def health(self) -> RendererHealth:
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                response = await client.get(f'{self.base_url}/health', headers=self._headers())
                response.raise_for_status()
                data = response.json()
            return RendererHealth(
                status=str(data.get('status', 'degraded')),
                backend=data.get('backend'),
                error=data.get('error'),
            )
        except Exception as exc:
            return RendererHealth(status='degraded', error=f'{type(exc).__name__}: {exc}')

    async def prepare_source(self, data: bytes, content_type: str) -> str:
        filename = 'source.jpg' if content_type == 'image/jpeg' else 'source.png' if content_type == 'image/png' else 'source.webp'
        headers = self._headers()
        headers['Content-Type'] = content_type
        async with httpx.AsyncClient(timeout=180.0) as client:
            response = await client.post(
                f'{self.base_url}/v1/sessions',
                json={'source_type': 'image'},
                headers=self._headers(),
            )
            response.raise_for_status()
            session_id = response.json()['session_id']

            response = await client.post(
                f'{self.base_url}/v1/sessions/{session_id}/source',
                files={'file': (filename, data, content_type)},
                headers=self._headers(),
            )
            response.raise_for_status()
            payload = response.json()
            handle = payload.get('source_handle')
            if not handle:
                raise RuntimeError('renderer returned an invalid source handle')
            return session_id

    async def render_frame(
        self,
        source_handle: str,
        pose: list[float],
        expression: list[float],
        landmarks: list[float],
        eye_ratio: float | None = None,
        lip_ratio: float | None = None,
    ) -> dict[str, Any]:
        payload = {
            'timestamp_ms': 0,
            'pose': pose,
            'expression': expression,
            'landmarks': landmarks,
            'eye_ratio': eye_ratio,
            'lip_ratio': lip_ratio,
        }
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(
                f'{self.base_url}/v1/render/frame',
                params={'session_id': source_handle},
                json=payload,
                headers=self._headers(),
            )
            response.raise_for_status()
            result = response.json()

        if result.get('status') != 'rendered' or not result.get('image_base64'):
            raise RuntimeError('renderer returned no rendered frame')
        return result
