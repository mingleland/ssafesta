from __future__ import annotations

from redis.asyncio import Redis

from app.core.redis import create_redis_client


def test_create_redis_client_returns_redis_configured_for_text_values() -> None:
    client = create_redis_client("redis://localhost:6379/0")

    assert isinstance(client, Redis)
    assert client.get_connection_kwargs()["decode_responses"] is True
