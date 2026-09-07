"""Verify the FastAPI->Spring booth-access client's parsing, timeout, and retry."""

from __future__ import annotations

import httpx
import pytest

from app.clients.spring_booth_access import (
    BoothAccessResult,
    SpringBoothAccessClient,
    SpringBoothAccessUnavailable,
)


def _client(handler, *, timeout_seconds: float = 1.0) -> SpringBoothAccessClient:
    transport = httpx.MockTransport(handler)
    http_client = httpx.AsyncClient(transport=transport)
    return SpringBoothAccessClient(
        base_url="http://spring.internal:8080",
        service_token="ai-to-spring-token-1",
        timeout_seconds=timeout_seconds,
        client=http_client,
    )


@pytest.mark.asyncio
async def test_allowed_response_is_parsed() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/ai/booth-access"
        assert dict(request.url.params) == {"boothId": "7", "agentId": "3"}
        assert request.headers["Authorization"] == "Bearer ai-to-spring-token-1"
        return httpx.Response(
            200,
            json={
                "allowed": True,
                "boothId": 7,
                "agentId": 3,
                "agentStatus": "ACTIVE",
                "leaseEndsAt": "2026-09-03T12:00:00Z",
                "remainingSeconds": 1200,
                "serverTime": "2026-09-03T11:40:00Z",
            },
        )

    result = await _client(handler).check(booth_id=7, agent_id=3)

    assert result == BoothAccessResult(
        allowed=True, lease_ends_at="2026-09-03T12:00:00Z", denial_code=None
    )


@pytest.mark.asyncio
async def test_denied_response_carries_denial_code() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "allowed": False,
                "boothId": 7,
                "agentId": 3,
                "leaseEndsAt": None,
                "remainingSeconds": 0,
                "serverTime": "2026-09-03T11:40:00Z",
                "denialCode": "BOOTH_LEASE_EXPIRED",
            },
        )

    result = await _client(handler).check(booth_id=7, agent_id=3)

    assert result == BoothAccessResult(
        allowed=False, lease_ends_at=None, denial_code="BOOTH_LEASE_EXPIRED"
    )


@pytest.mark.asyncio
async def test_retries_once_on_timeout_then_succeeds() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.TimeoutException("timed out")
        return httpx.Response(
            200,
            json={
                "allowed": True,
                "boothId": 7,
                "agentId": 3,
                "agentStatus": "ACTIVE",
                "leaseEndsAt": "2026-09-03T12:00:00Z",
                "remainingSeconds": 1200,
                "serverTime": "2026-09-03T11:40:00Z",
            },
        )

    result = await _client(handler).check(booth_id=7, agent_id=3)

    assert attempts == 2
    assert result.allowed is True


@pytest.mark.asyncio
async def test_raises_unavailable_after_second_timeout() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        raise httpx.TimeoutException("timed out")

    with pytest.raises(SpringBoothAccessUnavailable):
        await _client(handler).check(booth_id=7, agent_id=3)

    assert attempts == 2


@pytest.mark.asyncio
async def test_raises_unavailable_on_connection_error_without_retrying_forever() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        raise httpx.ConnectError("refused")

    with pytest.raises(SpringBoothAccessUnavailable):
        await _client(handler).check(booth_id=7, agent_id=3)

    assert attempts == 2


@pytest.mark.asyncio
async def test_raises_unavailable_on_malformed_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"unexpected": "shape"})

    with pytest.raises(SpringBoothAccessUnavailable):
        await _client(handler).check(booth_id=7, agent_id=3)


@pytest.mark.asyncio
async def test_raises_unavailable_on_401() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401)

    with pytest.raises(SpringBoothAccessUnavailable):
        await _client(handler).check(booth_id=7, agent_id=3)
