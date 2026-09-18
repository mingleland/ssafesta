"""FastAPI->Spring 문서 처리 결과 client 테스트 (S15P21A604-124).

계약: specs/007-ai-agent-document/contracts/document-result-api.yaml.
재시도(S15P21A604-125)는 별도로 검증한다 — 여기서는 단발 호출의 요청·응답 매핑만
확인한다.
"""

from __future__ import annotations

import json

import httpx
import pytest

from app.clients.spring_document_result import (
    ChunkBatchItem,
    SpringDocumentResultClient,
    SpringDocumentResultJobGone,
    SpringDocumentResultStaleAttempt,
    SpringDocumentResultUnavailable,
    SpringDocumentResultValidationFailed,
)
from app.services.context_service import ExtractedProjectFacts


def _client(handler) -> SpringDocumentResultClient:
    transport = httpx.MockTransport(handler)
    http_client = httpx.AsyncClient(transport=transport)
    return SpringDocumentResultClient(
        base_url="http://spring.internal:8080",
        service_token="ai-to-spring-token-1",
        timeout_seconds=3.0,
        client=http_client,
    )


def _chunk(chunk_no: int = 0) -> ChunkBatchItem:
    return ChunkBatchItem(
        chunk_no=chunk_no,
        content="본문",
        embedding=(0.1, 0.2, 0.3),
        embedding_model_id="text-embedding-3-large",
        page_number=1,
        section=None,
    )


@pytest.mark.asyncio
async def test_chunk_batch_posts_expected_body_and_path() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/ai/document-jobs/501/chunk-batches"
        assert request.headers["Authorization"] == "Bearer ai-to-spring-token-1"
        body = json.loads(request.content)
        assert body == {
            "attemptNo": 0,
            "batchSeq": 2,
            "chunks": [
                {
                    "chunkNo": 0,
                    "content": "본문",
                    "embedding": [0.1, 0.2, 0.3],
                    "embeddingModelId": "text-embedding-3-large",
                    "pageNumber": 1,
                    "section": None,
                }
            ],
        }
        return httpx.Response(204)

    await _client(handler).chunk_batch(
        job_id=501, attempt_no=0, batch_seq=2, chunks=[_chunk()]
    )


@pytest.mark.asyncio
async def test_finalize_posts_expected_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/ai/document-jobs/501/finalize"
        body = json.loads(request.content)
        assert body == {
            "attemptNo": 0,
            "sourceHash": "a" * 64,
            "totalChunkCount": 3,
            "embeddingModelId": "text-embedding-3-large",
        }
        return httpx.Response(204)

    await _client(handler).finalize(
        job_id=501,
        attempt_no=0,
        source_hash="a" * 64,
        total_chunk_count=3,
        embedding_model_id="text-embedding-3-large",
    )


@pytest.mark.asyncio
async def test_finalize_includes_extracted_project_facts_when_available() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert json.loads(request.content)["projectFacts"] == {
            "targetAudience": "프로젝트를 전시하고 싶은 교육생",
            "techStack": "FastAPI, Spring Boot, React, Unity",
        }
        return httpx.Response(204)

    await _client(handler).finalize(
        job_id=501,
        attempt_no=0,
        source_hash="a" * 64,
        total_chunk_count=3,
        embedding_model_id="text-embedding-3-large",
        project_facts=ExtractedProjectFacts(
            target_audience="프로젝트를 전시하고 싶은 교육생",
            tech_stack="FastAPI, Spring Boot, React, Unity",
        ),
    )


@pytest.mark.asyncio
async def test_heartbeat_posts_expected_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/ai/document-jobs/501/heartbeat"
        assert json.loads(request.content) == {"attemptNo": 1}
        return httpx.Response(204)

    await _client(handler).heartbeat(job_id=501, attempt_no=1)


@pytest.mark.asyncio
async def test_failed_posts_expected_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/ai/document-jobs/501/failed"
        assert json.loads(request.content) == {
            "attemptNo": 0,
            "failureCode": "SOURCE_HASH_MISMATCH",
            "retryable": False,
            "message": "hash mismatch",
        }
        return httpx.Response(204)

    await _client(handler).failed(
        job_id=501,
        attempt_no=0,
        failure_code="SOURCE_HASH_MISMATCH",
        retryable=False,
        message="hash mismatch",
    )


@pytest.mark.asyncio
async def test_failed_without_message_omits_null_message_key() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body == {
            "attemptNo": 0,
            "failureCode": "PARSE_FAILED",
            "retryable": True,
        }
        return httpx.Response(204)

    await _client(handler).failed(
        job_id=501, attempt_no=0, failure_code="PARSE_FAILED", retryable=True
    )


@pytest.mark.asyncio
async def test_raises_stale_attempt_on_409() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(409, json={"code": "JOB_ATTEMPT_STALE"})

    with pytest.raises(SpringDocumentResultStaleAttempt):
        await _client(handler).heartbeat(job_id=501, attempt_no=0)


@pytest.mark.asyncio
async def test_raises_job_gone_on_410() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(410, json={"code": "JOB_GONE"})

    with pytest.raises(SpringDocumentResultJobGone):
        await _client(handler).heartbeat(job_id=501, attempt_no=0)


@pytest.mark.asyncio
async def test_raises_validation_failed_on_400() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={"code": "VALIDATION_FAILED"})

    with pytest.raises(SpringDocumentResultValidationFailed):
        await _client(handler).finalize(
            job_id=501,
            attempt_no=0,
            source_hash="a" * 64,
            total_chunk_count=1,
            embedding_model_id="m",
        )


@pytest.mark.asyncio
async def test_raises_unavailable_on_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.TimeoutException("timed out")

    with pytest.raises(SpringDocumentResultUnavailable):
        await _client(handler).heartbeat(job_id=501, attempt_no=0)


@pytest.mark.asyncio
async def test_raises_unavailable_on_unexpected_status() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    with pytest.raises(SpringDocumentResultUnavailable):
        await _client(handler).heartbeat(job_id=501, attempt_no=0)
