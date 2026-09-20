"""S15P21A604-823 — 실제 GMS 모델의 주제 무관 질문 회피를 옵트인으로 검증한다."""

import os

import pytest
from pydantic import SecretStr

from app.clients.spring_chunk_search import RetrievedChunk
from app.providers.llm import LLMRequest
from app.providers.managed_llm import ManagedLLMProvider
from app.services.context_service import AgentPromptConfig, PromptBuilder

pytestmark = pytest.mark.live_provider

OFF_TOPIC_REFUSAL = "부스 관련 질문에만 답변할 수 있습니다."
NO_EVIDENCE = "문서에서 확인할 수 없습니다."


class _CharTokenCounter:
    def count_request(self, request: LLMRequest) -> int:
        return sum(len(message.content) for message in request.messages)


def _gms_key() -> SecretStr:
    if os.getenv("RUN_GMS_LIVE_TESTS") != "1":
        pytest.skip("RUN_GMS_LIVE_TESTS=1일 때만 실제 GMS Credit을 사용한다.")
    value = os.getenv("GMS_API_KEY", "").strip()
    if not value:
        pytest.skip("GMS_API_KEY가 주입되지 않았다.")
    return SecretStr(value)


def _agent() -> AgentPromptConfig:
    return AgentPromptConfig(
        role="GUIDE",
        tone="FRIENDLY",
        response_length="SHORT",
        system_prompt="등록 문서에 근거해 부스 이용을 안내한다.",
        forbidden_topics=(),
    )


def _chunk() -> RetrievedChunk:
    return RetrievedChunk(
        chunk_id=1,
        document_id=1,
        content="이 부스는 오전 10시부터 오후 6시까지 운영합니다.",
        page_number=1,
        section=None,
        original_filename="안내문.pdf",
        distance=0.1,
    )


async def _answer(llm: ManagedLLMProvider, question: str, chunks: tuple[RetrievedChunk, ...]) -> str:
    request = PromptBuilder(
        token_counter=_CharTokenCounter(), token_budget=100_000
    ).build(agent=_agent(), question=question, chunks=chunks).request
    return "".join(token.text async for token in llm.stream(request)).strip()


@pytest.mark.asyncio
async def test_live_llm_distinguishes_off_topic_grounded_and_missing_evidence() -> None:
    llm = ManagedLLMProvider(
        api_base_url="https://gms.ssafy.io/gmsapi/api.openai.com",
        api_path="/v1/chat/completions",
        api_key=_gms_key(),
        model_id="gpt-4.1-mini",
        temperature=0.0,
    )
    try:
        off_topic = await _answer(llm, "오늘 점심은 무엇을 먹을까요?", (_chunk(),))
        grounded = await _answer(llm, "이 부스 운영시간은 언제인가요?", (_chunk(),))
        missing = await _answer(llm, "행사장 주차 위치는 어디인가요?", ())
    finally:
        await llm.aclose()

    assert off_topic == OFF_TOPIC_REFUSAL
    assert "오전 10시" in grounded
    assert OFF_TOPIC_REFUSAL not in grounded
    assert missing == NO_EVIDENCE
