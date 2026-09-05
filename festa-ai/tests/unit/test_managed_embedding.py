"""Managed Embedding이 GMS 응답을 안전한 1536차원 batch로 변환하는지 검증한다."""

import json
from types import SimpleNamespace

import httpx
import pytest
from pydantic import SecretStr

from app.providers.factory import create_embedding_provider
from app.providers.managed_embedding import (
    ManagedEmbeddingError,
    ManagedEmbeddingProvider,
)
from app.providers.mock import MockEmbeddingProvider


def _provider(client: httpx.AsyncClient) -> ManagedEmbeddingProvider:
    return ManagedEmbeddingProvider(
        api_base_url="https://gms.example.test",
        api_path="/v1/embeddings",
        api_key=SecretStr("never-log-this-key"),
        model_id="gms-embedding-v1",
        client=client,
    )


@pytest.mark.asyncio
async def test_managed_embedding_returns_vectors_in_input_index_order() -> None:
    first_vector = [0.1] * 1536
    second_vector = [0.2] * 1536

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer never-log-this-key"
        payload = json.loads(request.content)
        assert payload["dimensions"] == 1536
        return httpx.Response(
            200,
            json={
                "data": [
                    {"index": 1, "embedding": second_vector},
                    {"index": 0, "embedding": first_vector},
                ]
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await _provider(client).embed(["first", "second"])

    assert result.model_id == "gms-embedding-v1"
    assert len(result.vectors) == 2
    assert result.vectors[0][0] == 0.1
    assert result.vectors[1][0] == 0.2
    assert all(len(vector) == 1536 for vector in result.vectors)


@pytest.mark.asyncio
async def test_managed_embedding_rejects_wrong_dimension() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"data": [{"index": 0, "embedding": [0.1] * 1535}]},
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(
            ManagedEmbeddingError, match="EMBEDDING_DIMENSION_MISMATCH"
        ):
            await _provider(client).embed(["text"])


@pytest.mark.asyncio
async def test_managed_embedding_rejects_response_count_mismatch() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(
            ManagedEmbeddingError, match="EMBEDDING_INVALID_RESPONSE"
        ):
            await _provider(client).embed(["text"])


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "items",
    [
        [
            {"index": 0, "embedding": [0.1] * 1536},
            {"embedding": [0.2] * 1536},
        ],
        [
            {"index": 0, "embedding": [0.1] * 1536},
            {"index": 0, "embedding": [0.2] * 1536},
        ],
        [
            {"index": 0, "embedding": [0.1] * 1536},
            {"index": 2, "embedding": [0.2] * 1536},
        ],
    ],
    ids=["partially-missing", "duplicate", "out-of-range"],
)
async def test_managed_embedding_rejects_invalid_indexes(
    items: list[dict[str, object]],
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": items})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(
            ManagedEmbeddingError, match="EMBEDDING_INVALID_RESPONSE"
        ):
            await _provider(client).embed(["first", "second"][: len(items)])


@pytest.mark.asyncio
async def test_managed_embedding_preserves_order_when_indexes_are_absent() -> None:
    first_vector = [0.1] * 1536
    second_vector = [0.2] * 1536

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": [
                    {"embedding": first_vector},
                    {"embedding": second_vector},
                ]
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await _provider(client).embed(["first", "second"])

    assert result.vectors[0][0] == 0.1
    assert result.vectors[1][0] == 0.2


@pytest.mark.asyncio
async def test_managed_embedding_rejects_non_finite_values() -> None:
    invalid_vector = [0.1] * 1536
    invalid_vector[10] = float("nan")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"data": [{"index": 0, "embedding": invalid_vector}]},
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(
            ManagedEmbeddingError, match="EMBEDDING_INVALID_RESPONSE"
        ):
            await _provider(client).embed(["text"])


@pytest.mark.asyncio
async def test_provider_error_does_not_expose_secret_or_raw_body() -> None:
    raw_body = "provider-internal-secret-response"

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text=raw_body)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ManagedEmbeddingError) as exc_info:
            await _provider(client).embed(["text"])

    rendered = str(exc_info.value)
    assert exc_info.value.code == "EMBEDDING_PROVIDER_ERROR"
    assert exc_info.value.retryable is True
    assert exc_info.value.status_code == 500
    assert "never-log-this-key" not in rendered
    assert raw_body not in rendered


@pytest.mark.asyncio
async def test_empty_batch_does_not_call_provider() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(500)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await _provider(client).embed([])

    assert result.vectors == ()
    assert calls == 0


def test_factory_selects_mock_provider_without_managed_settings() -> None:
    settings = SimpleNamespace(embedding_provider="mock")

    provider = create_embedding_provider(settings)  # type: ignore[arg-type]

    assert isinstance(provider, MockEmbeddingProvider)


@pytest.mark.asyncio
async def test_factory_selects_managed_provider_from_validated_settings() -> None:
    settings = SimpleNamespace(
        embedding_provider="gms",
        embedding_model_id="gms-embedding-v1",
        embedding_api_base_url="https://gms.example.test",
        embedding_api_path="/v1/embeddings",
        embedding_api_key=SecretStr("never-log-this-key"),
    )
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={}))
    async with httpx.AsyncClient(transport=transport) as client:
        provider = create_embedding_provider(  # type: ignore[arg-type]
            settings,
            client=client,
        )

    assert isinstance(provider, ManagedEmbeddingProvider)
    assert provider.model_id == "gms-embedding-v1"
