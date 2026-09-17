import asyncio

from renderer_client import RendererClient, RendererHealth


def test_renderer_health_type():
    health = RendererHealth(status='ready', backend='test')
    assert health.status == 'ready'
    assert health.backend == 'test'


def test_unconfigured_client_is_degraded():
    client = RendererClient('http://127.0.0.1:1')
    result = asyncio.run(client.health())
    assert result.status == 'degraded'
