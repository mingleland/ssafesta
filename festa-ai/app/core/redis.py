"""Redis connection for Conversation storage (spec 008 — 30-minute TTL)."""

from __future__ import annotations

from redis.asyncio import Redis


def create_redis_client(redis_url: str) -> Redis:
    return Redis.from_url(redis_url, decode_responses=True)
