"""AI_CONSULT 마커 통보 클라이언트의 계약 (spec 022 FR-003a, S15P21A604-955)."""

from __future__ import annotations

import json

import httpx
import pytest

from app.clients.spring_mission import (
    SpringMissionMarkerClient,
    SpringMissionMarkerUnavailable,
)


def _client(handler) -> SpringMissionMarkerClient:
    return SpringMissionMarkerClient(
        base_url="http://spring:8080",
        service_token="ai-to-spring-token-1",
        timeout_seconds=1.0,
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )


@pytest.mark.asyncio
async def test_marks_with_the_service_token_and_the_authenticated_user_id() -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("Authorization")
        seen["body"] = json.loads(request.read())
        return httpx.Response(204)

    await _client(handler).mark_ai_consult(user_id=42)

    assert seen["url"] == "http://spring:8080/internal/ai/mission/ai-consult"
    assert seen["auth"] == "Bearer ai-to-spring-token-1"
    assert seen["body"] == {"userId": 42}


@pytest.mark.asyncio
@pytest.mark.parametrize("status_code", [200, 202, 302, 307, 400, 401, 500, 503])
async def test_anything_but_204_is_one_exception_type(status_code: int) -> None:
    """204 만 마커가 쓰였다는 답이다 — 리다이렉트나 다른 2xx 를 성공으로 읽으면 안 된다."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status_code)

    with pytest.raises(SpringMissionMarkerUnavailable):
        await _client(handler).mark_ai_consult(user_id=42)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "error",
    [httpx.TimeoutException("timed out"), httpx.ConnectError("no route")],
)
async def test_a_transport_failure_is_the_same_exception_type(error: Exception) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise error

    with pytest.raises(SpringMissionMarkerUnavailable):
        await _client(handler).mark_ai_consult(user_id=42)


@pytest.mark.asyncio
async def test_a_refusal_is_not_retried() -> None:
    """대화 생성 경로에 붙는 호출이라 재시도로 지연을 늘리지 않는다."""
    attempts = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(request)
        return httpx.Response(503)

    with pytest.raises(SpringMissionMarkerUnavailable):
        await _client(handler).mark_ai_consult(user_id=42)

    assert len(attempts) == 1
