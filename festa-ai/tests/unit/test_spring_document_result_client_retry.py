"""일시적 네트워크 실패에 대한 재시도 테스트 (S15P21A604-125).

409/410/400은 재시도 대상이 아니다 — attempt가 이미 무의미하거나 요청 자체가
틀렸다는 뜻이라 다시 보내도 같은 결과다. timeout·connect 실패·5xx만 재시도한다.
계산(Embedding)까지 끝낸 결과를 한 번의 네트워크 실패로 버리지 않기 위함이다.
"""

from __future__ import annotations

import httpx
import pytest

from app.clients.spring_document_result import (
    SpringDocumentResultClient,
    SpringDocumentResultJobGone,
    SpringDocumentResultStaleAttempt,
    SpringDocumentResultUnavailable,
    SpringDocumentResultValidationFailed,
)


def _client(handler, *, max_attempts: int = 3) -> SpringDocumentResultClient:
    transport = httpx.MockTransport(handler)
    http_client = httpx.AsyncClient(transport=transport)
    return SpringDocumentResultClient(
        base_url="http://spring.internal:8080",
        service_token="ai-to-spring-token-1",
        timeout_seconds=3.0,
        client=http_client,
        max_attempts=max_attempts,
        retry_backoff_seconds=0.0,
    )


@pytest.mark.asyncio
async def test_heartbeat_retries_after_timeout_then_succeeds() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.TimeoutException("timed out")
        return httpx.Response(204)

    await _client(handler).heartbeat(job_id=501, attempt_no=0)

    assert attempts == 2


@pytest.mark.asyncio
async def test_chunk_batch_retries_after_5xx_then_succeeds() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            return httpx.Response(503)
        return httpx.Response(204)

    await _client(handler, max_attempts=3).chunk_batch(
        job_id=501,
        attempt_no=0,
        batch_seq=0,
        chunks=[],
    )

    assert attempts == 3


@pytest.mark.asyncio
async def test_gives_up_after_max_attempts_and_raises_unavailable() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        raise httpx.ConnectError("connection refused")

    with pytest.raises(SpringDocumentResultUnavailable):
        await _client(handler, max_attempts=3).heartbeat(job_id=501, attempt_no=0)

    assert attempts == 3


@pytest.mark.asyncio
async def test_does_not_retry_on_stale_attempt_409() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(409)

    with pytest.raises(SpringDocumentResultStaleAttempt):
        await _client(handler).heartbeat(job_id=501, attempt_no=0)

    assert attempts == 1


@pytest.mark.asyncio
async def test_does_not_retry_on_job_gone_410() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(410)

    with pytest.raises(SpringDocumentResultJobGone):
        await _client(handler).heartbeat(job_id=501, attempt_no=0)

    assert attempts == 1


@pytest.mark.asyncio
async def test_does_not_retry_on_validation_failed_400() -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(400, json={"code": "VALIDATION_FAILED"})

    with pytest.raises(SpringDocumentResultValidationFailed):
        await _client(handler).heartbeat(job_id=501, attempt_no=0)

    assert attempts == 1
