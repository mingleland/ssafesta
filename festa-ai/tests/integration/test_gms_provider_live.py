"""명시적으로 활성화할 때만 실제 GMS LLM·Embedding 계약을 회귀 검증한다."""

import logging
import os

import pytest
from pydantic import SecretStr

from app.providers.llm import LLMMessage, LLMRequest
from app.providers.managed_embedding import ManagedEmbeddingProvider
from app.providers.managed_llm import ManagedLLMProvider


pytestmark = pytest.mark.live_provider


def _gms_key() -> SecretStr:
    if os.getenv("RUN_GMS_LIVE_TESTS") != "1":
        pytest.skip("RUN_GMS_LIVE_TESTS=1일 때만 실제 GMS Credit을 사용한다.")
    value = os.getenv("GMS_API_KEY", "").strip()
    if not value:
        pytest.skip("GMS_API_KEY가 주입되지 않았다.")
    return SecretStr(value)


@pytest.mark.asyncio
async def test_live_gms_embedding_and_llm_stream(
    caplog: pytest.LogCaptureFixture,
) -> None:
    key = _gms_key()
    embedding = ManagedEmbeddingProvider(
        api_base_url="https://gms.ssafy.io/gmsapi/api.openai.com",
        api_path="/v1/embeddings",
        api_key=key,
        model_id="text-embedding-3-large",
    )
    llm = ManagedLLMProvider(
        api_base_url="https://gms.ssafy.io/gmsapi/api.openai.com",
        api_path="/v1/chat/completions",
        api_key=key,
        model_id="gpt-4.1-mini",
        temperature=0.0,
    )
    try:
        with caplog.at_level(logging.INFO):
            vectors = await embedding.embed(["SSAFY FESTA GMS Provider 회귀 테스트"])
            tokens = [
                token.text
                async for token in llm.stream(
                    LLMRequest(
                        messages=(
                            LLMMessage(
                                role="system",
                                content="한 단어로만 답하고 비밀값을 추측하지 마세요.",
                            ),
                            LLMMessage(role="user", content="준비됐나요?"),
                        ),
                        max_output_tokens=20,
                    )
                )
            ]
    finally:
        await embedding.aclose()
        await llm.aclose()

    assert len(vectors.vectors) == 1
    assert len(vectors.vectors[0]) == 1536
    assert "".join(tokens).strip()
    assert "gms_embedding_usage" in caplog.text
    assert "gms_llm_usage" in caplog.text
