"""Verify the FastAPI->Spring chunk-search client's request/response mapping."""

from __future__ import annotations

import json

import httpx
import pytest

from app.clients.spring_chunk_search import (
    ChunkScope,
    RetrievedChunk,
    SpringChunkSearchClient,
    SpringChunkSearchUnavailable,
)


def _client(handler, *, timeout_seconds: float = 3.5) -> SpringChunkSearchClient:
    transport = httpx.MockTransport(handler)
    http_client = httpx.AsyncClient(transport=transport)
    return SpringChunkSearchClient(
        base_url="http://spring.internal:8080",
        service_token="ai-to-spring-token-1",
        timeout_seconds=timeout_seconds,
        client=http_client,
    )


@pytest.mark.asyncio
async def test_search_sends_scope_embedding_and_top_k() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/internal/ai/chunk-search"
        assert request.headers["Authorization"] == "Bearer ai-to-spring-token-1"
        body = json.loads(request.content)
        assert body == {
            "boothId": 7,
            "agentId": 3,
            "queryEmbedding": [0.1, 0.2],
            "topK": 5,
        }
        return httpx.Response(200, json={"items": []})

    result = await _client(handler).search(
        scope=ChunkScope(booth_id=7, agent_id=3),
        query_embedding=(0.1, 0.2),
        top_k=5,
    )

    assert result == ()


@pytest.mark.asyncio
async def test_items_are_parsed_into_retrieved_chunks() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "items": [
                    {
                        "content": "본문",
                        "chunkNo": 2,
                        "pageNumber": 5,
                        "section": "3장",
                        "documentId": 9001,
                        "originalFilename": "guide.pdf",
                        "distance": 0.12,
                    }
                ]
            },
        )

    result = await _client(handler).search(
        scope=ChunkScope(booth_id=7, agent_id=3),
        query_embedding=(0.1,),
        top_k=5,
    )

    assert result == (
        RetrievedChunk(
            document_id=9001,
            chunk_id=2,
            content="본문",
            page_number=5,
            section="3장",
            original_filename="guide.pdf",
            distance=0.12,
        ),
    )


@pytest.mark.asyncio
async def test_null_page_number_and_section_are_preserved() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "items": [
                    {
                        "content": "본문",
                        "chunkNo": 0,
                        "pageNumber": None,
                        "section": None,
                        "documentId": 1,
                        "originalFilename": "readme.md",
                        "distance": 0.5,
                    }
                ]
            },
        )

    result = await _client(handler).search(
        scope=ChunkScope(booth_id=7, agent_id=3), query_embedding=(0.1,), top_k=1
    )

    assert result[0].page_number is None
    assert result[0].section is None


@pytest.mark.asyncio
async def test_raises_unavailable_on_non_200() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={"code": "VALIDATION_FAILED"})

    with pytest.raises(SpringChunkSearchUnavailable):
        await _client(handler).search(
            scope=ChunkScope(booth_id=7, agent_id=3), query_embedding=(0.1,), top_k=1
        )


@pytest.mark.asyncio
async def test_raises_unavailable_on_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.TimeoutException("timed out")

    with pytest.raises(SpringChunkSearchUnavailable):
        await _client(handler).search(
            scope=ChunkScope(booth_id=7, agent_id=3), query_embedding=(0.1,), top_k=1
        )


@pytest.mark.asyncio
async def test_raises_unavailable_on_malformed_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"unexpected": "shape"})

    with pytest.raises(SpringChunkSearchUnavailable):
        await _client(handler).search(
            scope=ChunkScope(booth_id=7, agent_id=3), query_embedding=(0.1,), top_k=1
        )


@pytest.mark.asyncio
async def test_raises_unavailable_on_malformed_item() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"items": [{"content": "본문"}]})

    with pytest.raises(SpringChunkSearchUnavailable):
        await _client(handler).search(
            scope=ChunkScope(booth_id=7, agent_id=3), query_embedding=(0.1,), top_k=1
        )


def test_chunk_scope_rejects_non_positive_ids() -> None:
    with pytest.raises(ValueError):
        ChunkScope(booth_id=0, agent_id=1)
    with pytest.raises(ValueError):
        ChunkScope(booth_id=1, agent_id=-1)
