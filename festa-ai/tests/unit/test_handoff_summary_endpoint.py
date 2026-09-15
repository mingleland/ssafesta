"""S15P21A604-139 — handoff-summary endpoint의 HTTP 계약 검증.

`require_spring_service_token`을 override하지 않고 실제로 태워서, 이 라우트가
`require_member`(다른 3개 라우트)가 아니라 Spring 전용 인증을 쓴다는 결정을
회귀 테스트로 고정한다(`test_document_process_endpoint.py`와 같은 패턴).
"""

from __future__ import annotations

import httpx
import pytest
from fastapi import FastAPI

from app.api.errors import ApiError, api_error_handler
from app.api.v1.conversations import get_handoff_summary_service, router
from app.services.handoff_summary_service import (
    ConversationNotFound,
    HandoffSummary,
    HandoffSummaryGenerationFailed,
)

AUTH_HEADERS = {"Authorization": "Bearer spring-token"}


class FakeHandoffSummaryService:
    def __init__(
        self, *, result: HandoffSummary | None = None, error: Exception | None = None
    ) -> None:
        self.result = result
        self.error = error
        self.calls: list[str] = []

    async def summarize(self, conversation_id: str) -> HandoffSummary:
        self.calls.append(conversation_id)
        if self.error is not None:
            raise self.error
        assert self.result is not None
        return self.result


def _app(service: FakeHandoffSummaryService) -> FastAPI:
    app = FastAPI()
    app.state.settings = type(
        "Settings", (), {"internal_spring_to_ai_tokens": ["spring-token"]}
    )()
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(router, prefix="/ai/v1")
    app.dependency_overrides[get_handoff_summary_service] = lambda: service
    return app


@pytest.mark.asyncio
async def test_returns_200_with_exactly_the_four_contract_fields() -> None:
    service = FakeHandoffSummaryService(
        result=HandoffSummary(
            summary="요약 문장",
            topics=("주제1", "주제2"),
            last_user_intent="담당자 연결 원함",
        )
    )
    transport = httpx.ASGITransport(app=_app(service))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/ai/v1/conversations/conv_abc/handoff-summary", headers=AUTH_HEADERS
        )

    assert response.status_code == 200
    body = response.json()
    # D11 회귀 가드 — question/answer 등 원문 필드가 하나라도 섞여 나오면 실패한다.
    assert set(body.keys()) == {"conversationId", "summary", "topics", "lastUserIntent"}
    assert body == {
        "conversationId": "conv_abc",
        "summary": "요약 문장",
        "topics": ["주제1", "주제2"],
        "lastUserIntent": "담당자 연결 원함",
    }
    assert service.calls == ["conv_abc"]


@pytest.mark.asyncio
async def test_rejects_missing_service_token() -> None:
    service = FakeHandoffSummaryService(
        result=HandoffSummary(summary="s", topics=(), last_user_intent="")
    )
    transport = httpx.ASGITransport(app=_app(service))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/ai/v1/conversations/conv_abc/handoff-summary")

    assert response.status_code == 401
    assert service.calls == []


@pytest.mark.asyncio
async def test_conversation_not_found_returns_404() -> None:
    service = FakeHandoffSummaryService(error=ConversationNotFound("conv_missing"))
    transport = httpx.ASGITransport(app=_app(service))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/ai/v1/conversations/conv_missing/handoff-summary", headers=AUTH_HEADERS
        )

    assert response.status_code == 404
    assert response.json()["code"] == "CONVERSATION_NOT_FOUND"


@pytest.mark.asyncio
async def test_generation_failure_returns_503_with_its_code() -> None:
    service = FakeHandoffSummaryService(
        error=HandoffSummaryGenerationFailed("LLM_TIMEOUT")
    )
    transport = httpx.ASGITransport(app=_app(service))

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/ai/v1/conversations/conv_abc/handoff-summary", headers=AUTH_HEADERS
        )

    assert response.status_code == 503
    assert response.json()["code"] == "LLM_TIMEOUT"
