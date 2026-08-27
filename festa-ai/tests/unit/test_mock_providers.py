"""Mock LLM·Embedding이 외부 호출 없이 결정적으로 동작하는지 검증한다."""

import math

import pytest

from app.providers.embedding import EmbeddingProvider
from app.providers.llm import LLMMessage, LLMProvider, LLMRequest
from app.providers.mock import MockEmbeddingProvider, MockLLMProvider


@pytest.mark.asyncio
async def test_mock_embedding_is_deterministic_across_instances() -> None:
    first_provider = MockEmbeddingProvider()
    second_provider = MockEmbeddingProvider()

    first = await first_provider.embed(["SSAFY FESTA"])
    second = await second_provider.embed(["SSAFY FESTA"])

    assert isinstance(first_provider, EmbeddingProvider)
    assert first == second
    assert first.model_id == "mock-hash-embedding-1536-v1"
    assert len(first.vectors) == 1
    assert len(first.vectors[0]) == 1536
    assert math.isclose(math.sqrt(sum(value * value for value in first.vectors[0])), 1.0)


@pytest.mark.asyncio
async def test_mock_embedding_preserves_batch_order_and_records_calls() -> None:
    provider = MockEmbeddingProvider()

    result = await provider.embed(["first", "second", "first"])

    assert provider.calls == [("first", "second", "first")]
    assert len(result.vectors) == 3
    assert result.vectors[0] != result.vectors[1]
    assert result.vectors[0] == result.vectors[2]


@pytest.mark.asyncio
async def test_mock_embedding_accepts_an_empty_batch() -> None:
    provider = MockEmbeddingProvider()

    result = await provider.embed([])

    assert result.vectors == ()
    assert provider.calls == [()]


@pytest.mark.asyncio
async def test_mock_llm_is_deterministic_and_streams_normalized_tokens() -> None:
    request = LLMRequest(
        messages=(
            LLMMessage(role="system", content="문서로만 답하세요."),
            LLMMessage(role="user", content="프로젝트의 핵심은 무엇인가요?"),
        )
    )
    first_provider = MockLLMProvider()
    second_provider = MockLLMProvider()

    first = "".join(
        [token.text async for token in first_provider.stream(request)]
    )
    second = "".join(
        [token.text async for token in second_provider.stream(request)]
    )

    assert isinstance(first_provider, LLMProvider)
    assert first == second
    assert first == "[Mock 답변] 프로젝트의 핵심은 무엇인가요?"
    assert first_provider.calls == [request]


@pytest.mark.asyncio
async def test_mock_llm_uses_only_the_latest_user_message() -> None:
    request = LLMRequest(
        messages=(
            LLMMessage(role="user", content="이전 질문"),
            LLMMessage(role="assistant", content="이전 답변"),
            LLMMessage(role="user", content="현재 질문"),
        )
    )
    provider = MockLLMProvider()

    response = "".join([token.text async for token in provider.stream(request)])

    assert response == "[Mock 답변] 현재 질문"


@pytest.mark.asyncio
async def test_mock_llm_returns_a_fixed_message_without_user_input() -> None:
    provider = MockLLMProvider()
    request = LLMRequest(messages=(LLMMessage(role="system", content="policy"),))

    response = "".join([token.text async for token in provider.stream(request)])

    assert response == "[Mock 답변] 사용자 질문이 없습니다."
