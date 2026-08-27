"""LLM·Embedding 구현체가 따라야 할 최소 Provider 계약을 고정한다."""

from collections.abc import AsyncIterator, Sequence
from dataclasses import FrozenInstanceError

import pytest

from app.providers.embedding import EmbeddingBatch, EmbeddingProvider
from app.providers.llm import LLMMessage, LLMProvider, LLMRequest, LLMToken


class StubEmbeddingProvider:
    model_id = "stub-embedding-v1"
    dimension = 1536

    async def embed(self, texts: Sequence[str]) -> EmbeddingBatch:
        vectors = tuple((float(index),) * self.dimension for index, _ in enumerate(texts))
        return EmbeddingBatch(model_id=self.model_id, vectors=vectors)


class StubLLMProvider:
    model_id = "stub-llm-v1"

    async def stream(self, request: LLMRequest) -> AsyncIterator[LLMToken]:
        for message in request.messages:
            yield LLMToken(text=message.content)


@pytest.mark.asyncio
async def test_embedding_protocol_preserves_batch_order() -> None:
    provider = StubEmbeddingProvider()

    assert isinstance(provider, EmbeddingProvider)

    result = await provider.embed(["first", "second"])

    assert result.model_id == provider.model_id
    assert len(result.vectors) == 2
    assert result.vectors[0][0] == 0.0
    assert result.vectors[1][0] == 1.0


@pytest.mark.asyncio
async def test_llm_protocol_exposes_normalized_token_stream() -> None:
    provider = StubLLMProvider()
    request = LLMRequest(
        messages=(
            LLMMessage(role="system", content="policy"),
            LLMMessage(role="user", content="question"),
        )
    )

    assert isinstance(provider, LLMProvider)

    tokens = [token.text async for token in provider.stream(request)]

    assert tokens == ["policy", "question"]


def test_provider_value_objects_are_immutable() -> None:
    message = LLMMessage(role="user", content="question")

    with pytest.raises(FrozenInstanceError):
        message.content = "changed"  # type: ignore[misc]
