"""S15P21A604-163 — 검색 경로에서 FastAPI가 자체적으로 부담하는 지연시간을 측정한다.

SC-006(P95 1초) 예산 전체가 아니라, 그 예산 안에서 AI(FastAPI) 쪽이 차지하는 몫만
잰다. 실질 병목(pgvector 쿼리)과 Top-K 포함률은 Spring 프로세스·DB 안에서
결정되므로 이 이슈의 범위 밖이다 — 그 쪽은 S15P21A604-521(Backend, Testcontainers
기반)이 담당한다.

두 구간을 분리해서 잰다:
- HTTP client 오버헤드: ``SpringChunkSearchClient``가 즉시 응답하는 fake Spring
  transport를 상대로 요청을 만들고 보내고 파싱하는 데 걸리는 시간. 네트워크·DB
  시간이 섞이지 않도록 Spring 응답 자체는 지연 없이 고정한다.
- 질의 임베딩 계산: 실제 GMS Embedding Provider 호출 시간. 실제 Credit을
  소비하므로 ``RUN_GMS_LIVE_TESTS=1``일 때만 켠다 (``test_gms_provider_live.py``와
  같은 옵트인 규약).
"""

from __future__ import annotations

import math
import os
import time
from collections.abc import Sequence

import httpx
import pytest
from pydantic import SecretStr

from app.clients.spring_chunk_search import ChunkScope, SpringChunkSearchClient
from app.providers.managed_embedding import ManagedEmbeddingProvider

ITEM_COUNT = 10
QUERY_EMBEDDING = tuple(0.001 * index for index in range(1536))


def _percentile(values: Sequence[float], percentile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = max(0, math.ceil(percentile * len(ordered)) - 1)
    return ordered[index]


def _instant_spring_handler(request: httpx.Request) -> httpx.Response:
    """DB·네트워크 지연 없이 즉시 응답한다 — client 쪽 오버헤드만 남긴다."""

    items = [
        {
            "documentId": 1,
            "chunkNo": index,
            "content": f"chunk-{index}",
            "pageNumber": 1,
            "section": None,
            "originalFilename": "sample.pdf",
            "distance": 0.1,
        }
        for index in range(ITEM_COUNT)
    ]
    return httpx.Response(200, json={"items": items})


def _client() -> SpringChunkSearchClient:
    transport = httpx.MockTransport(_instant_spring_handler)
    http_client = httpx.AsyncClient(transport=transport)
    return SpringChunkSearchClient(
        base_url="http://spring.internal:8080",
        service_token="ai-to-spring-token-1",
        timeout_seconds=3.0,
        client=http_client,
    )


@pytest.mark.asyncio
async def test_http_client_overhead_p95_is_well_under_search_budget() -> None:
    """SC-006 1초 예산 안에서 client 쪽 오버헤드가 무시할 수준인지 고정한다."""

    client = _client()
    scope = ChunkScope(booth_id=1, agent_id=1)
    latencies_ms: list[float] = []
    try:
        for _ in range(50):
            started = time.perf_counter()
            result = await client.search(scope=scope, query_embedding=QUERY_EMBEDDING, top_k=10)
            latencies_ms.append((time.perf_counter() - started) * 1000)
            assert len(result) == ITEM_COUNT
    finally:
        await client.aclose()

    p50 = _percentile(latencies_ms, 0.50)
    p95 = _percentile(latencies_ms, 0.95)

    # 여유 있게 100ms — 실 Spring 네트워크·DB 시간이 전혀 섞이지 않은 순수
    # client-side(직렬화+dispatch+파싱) 오버헤드라 SC-006 1초 예산의 극히 일부다.
    assert p95 < 100.0

    print(
        f"[S15P21A604-163] http_client_overhead p50={p50:.3f}ms p95={p95:.3f}ms "
        f"n={len(latencies_ms)}"
    )


def _gms_key() -> SecretStr:
    if os.getenv("RUN_GMS_LIVE_TESTS") != "1":
        pytest.skip("RUN_GMS_LIVE_TESTS=1일 때만 실제 GMS Credit을 사용한다.")
    value = os.getenv("GMS_API_KEY", "").strip()
    if not value:
        pytest.skip("GMS_API_KEY가 주입되지 않았다.")
    return SecretStr(value)


@pytest.mark.live_provider
@pytest.mark.asyncio
async def test_live_embedding_latency_p95() -> None:
    """실제 GMS 질의 임베딩 계산 지연시간을 P50/P95로 잰다 (SC-006 AI 쪽 몫)."""

    key = _gms_key()
    embedding = ManagedEmbeddingProvider(
        api_base_url="https://gms.ssafy.io/gmsapi/api.openai.com",
        api_path="/v1/embeddings",
        api_key=key,
        model_id="text-embedding-3-large",
    )
    latencies_ms: list[float] = []
    try:
        for index in range(15):
            started = time.perf_counter()
            batch = await embedding.embed((f"SSAFY FESTA 검색 지연시간 실측 질문 {index}",))
            latencies_ms.append((time.perf_counter() - started) * 1000)
            assert len(batch.vectors) == 1
            assert len(batch.vectors[0]) == 1536
    finally:
        await embedding.aclose()

    p50 = _percentile(latencies_ms, 0.50)
    p95 = _percentile(latencies_ms, 0.95)

    print(
        f"[S15P21A604-163] embedding_latency p50={p50:.3f}ms p95={p95:.3f}ms "
        f"n={len(latencies_ms)}"
    )
