"""Prove Conversation raw text leaves Redis on close and on the idle TTL (D11).

spec 008 FR-014/FR-028 and SC-012: an explicit close deletes the question and
answer immediately, a lost close falls back to the 30-minute idle TTL, and no
raw text reaches the application log on either path.

These wire the real repository, service, stream service and router together over
`fakeredis` — the parts that must not silently disagree are the close endpoint,
the conditional turn commit and the TTL, so all three run for real here.
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timedelta, timezone

import fakeredis
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.errors import ApiError, api_error_handler
from app.api.v1.conversations import get_conversation_service, get_stream_service, router
from app.core.auth import AuthenticatedMember, require_member
from app.repositories.conversation_repository import ConversationRepository
from app.services.conversation_service import ConversationService
from app.clients.spring_chunk_search import RetrievedChunk
from app.providers.llm import LLMRequest
from app.services.context_service import ContextBuildResult
from app.services.rag_service import NoReadyContextResult
from app.services.stream_service import ConversationStreamService
from tests.fakes.llm import FakeLLMProvider
from tests.fakes.spring_booth_access import FakeSpringBoothAccessClient
from app.clients.spring_booth_access import BoothAccessResult

_MEMBER = AuthenticatedMember(user_id=42)
_NOW = datetime(2026, 9, 3, 11, 40, tzinfo=timezone.utc)
_QUESTION = "무대 일정이 언제인가요"
_ANSWER_TOKENS = ("토요일", " 오후 두 시입니다")
_CHUNK_TEXT = "공연 일정 원문"


class _RagContextService:
    def __init__(self, *, no_ready: bool = False) -> None:
        self._no_ready = no_ready

    async def build(self, *, conversation, question):
        if self._no_ready:
            return NoReadyContextResult()
        return ContextBuildResult(
            request=LLMRequest(messages=()),
            included_chunks=(
                RetrievedChunk(
                    document_id=2001,
                    chunk_id=1,
                    content=_CHUNK_TEXT,
                    page_number=1,
                    section=None,
                    original_filename="일정.pdf",
                    distance=0.1,
                ),
            ),
            token_count=10,
            max_output_tokens=400,
            included_turn_count=0,
            omitted_chunk_count=0,
            omitted_turn_count=0,
            omitted_summary=False,
        )


def _harness(*, ttl_seconds: int = 1800, no_ready: bool = False):
    redis = fakeredis.FakeAsyncRedis(decode_responses=True)
    repository = ConversationRepository(redis, ttl_seconds=ttl_seconds)

    spring_client = FakeSpringBoothAccessClient()
    spring_client.result = BoothAccessResult(
        allowed=True,
        denial_code=None,
        lease_ends_at=(_NOW + timedelta(hours=1)).isoformat(),
    )
    conversation_service = ConversationService(
        spring_client=spring_client,
        repository=repository,
        ttl_seconds=ttl_seconds,
        clock=lambda: _NOW,
    )
    stream_service = ConversationStreamService(
        repository=repository,
        rag_context_service=_RagContextService(no_ready=no_ready),
        llm_provider=FakeLLMProvider(tokens=_ANSWER_TOKENS),
        ttl_seconds=ttl_seconds,
        clock=lambda: _NOW,
    )

    app = FastAPI()
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(router, prefix="/ai/v1")
    app.dependency_overrides[require_member] = lambda: _MEMBER
    app.dependency_overrides[get_conversation_service] = lambda: conversation_service
    app.dependency_overrides[get_stream_service] = lambda: stream_service
    return TestClient(app), repository, redis, stream_service


def _create(client: TestClient) -> str:
    response = client.post("/ai/v1/conversations", json={"boothId": 7, "agentId": 3})
    assert response.status_code == 201
    return response.json()["conversationId"]


def _ask(client: TestClient, conversation_id: str) -> str:
    response = client.post(
        f"/ai/v1/conversations/{conversation_id}/messages", json={"question": _QUESTION}
    )
    assert response.status_code == 200
    return response.text


def test_explicit_close_deletes_raw_text_and_later_questions_404() -> None:
    client, repository, redis, _ = _harness()
    conversation_id = _create(client)
    _ask(client, conversation_id)

    stored = asyncio.run(redis.get(f"conversation:{conversation_id}"))
    assert stored is not None
    # 종료 전에는 원문이 실제로 들어 있다 — 지워질 대상이 있다는 것부터 고정한다
    turn = json.loads(stored)["turns"][-1]
    assert turn["question"] == _QUESTION
    assert turn["answer"] == "".join(_ANSWER_TOKENS)

    assert client.delete(f"/ai/v1/conversations/{conversation_id}").status_code == 204

    assert asyncio.run(redis.exists(f"conversation:{conversation_id}")) == 0
    assert asyncio.run(repository.get(conversation_id)) is None
    followup = client.post(
        f"/ai/v1/conversations/{conversation_id}/messages", json={"question": _QUESTION}
    )
    assert followup.status_code == 404
    assert followup.json()["code"] == "CONVERSATION_NOT_FOUND"


def test_close_is_idempotent_and_a_second_call_still_returns_204() -> None:
    client, _, _, _ = _harness()
    conversation_id = _create(client)

    assert client.delete(f"/ai/v1/conversations/{conversation_id}").status_code == 204
    assert client.delete(f"/ai/v1/conversations/{conversation_id}").status_code == 204


def test_idle_ttl_removes_raw_text_when_the_close_call_is_lost() -> None:
    """FR-028 fallback — 1-second TTL stands in for the 30-minute production value."""
    client, repository, redis, _ = _harness(ttl_seconds=1)
    conversation_id = _create(client)
    _ask(client, conversation_id)

    ttl = asyncio.run(redis.ttl(f"conversation:{conversation_id}"))
    assert 0 < ttl <= 1
    asyncio.run(asyncio.sleep(1.15))

    assert asyncio.run(redis.exists(f"conversation:{conversation_id}")) == 0
    assert asyncio.run(repository.get(conversation_id)) is None


def test_no_raw_question_or_answer_reaches_the_application_log(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """FR-015 — identifiers, latency and error codes only, never the text."""
    client, _, _, _ = _harness()
    with caplog.at_level(logging.DEBUG):
        conversation_id = _create(client)
        body = _ask(client, conversation_id)
        client.delete(f"/ai/v1/conversations/{conversation_id}")

    # 답변은 토큰 이벤트로 쪼개져 나간다 — 나갔다는 것부터 고정하고, 그 다음에 로그를 본다
    for token in _ANSWER_TOKENS:
        assert token.strip() in body

    logged = " | ".join(record.getMessage() for record in caplog.records)
    # 저장 JSON 은 ensure_ascii 로 escape 되므로 두 형태 모두 본다 — blob 이 실려도 잡힌다
    for secret in (_QUESTION, *_ANSWER_TOKENS, _CHUNK_TEXT):
        assert secret.strip() not in logged
        assert json.dumps(secret.strip())[1:-1] not in logged


@pytest.mark.parametrize("no_ready", [False, True], ids=["answer", "no_ready_docs"])
def test_close_mid_stream_keeps_raw_text_out_of_redis_and_the_log(
    no_ready: bool,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """The drop path itself must not log the text it refused to store.

    Driven below HTTP because the close has to land while the stream is still
    running — which is the real flow: the FE keeps draining the SSE body after
    the overlay unmounts, so this generator reaches its commit either way.
    """
    client, repository, redis, stream_service = _harness(no_ready=no_ready)
    conversation_id = _create(client)
    conversation = asyncio.run(repository.get(conversation_id))
    assert conversation is not None

    async def stream_then_close() -> list[str]:
        events: list[str] = []
        async for event in stream_service.stream(
            conversation=conversation, question=_QUESTION
        ):
            events.append(event)
            if len(events) == 2:
                await repository.delete(conversation_id)  # 사용자가 닫았다 → DELETE
        return events

    with caplog.at_level(logging.DEBUG):
        events = asyncio.run(stream_then_close())

    assert '"type": "done"' in events[-1]  # 진행 중 응답은 끊지 않는다 (C-08 과 같은 결)
    assert asyncio.run(redis.exists(f"conversation:{conversation_id}")) == 0
    assert asyncio.run(repository.get(conversation_id)) is None

    logged = " | ".join(record.getMessage() for record in caplog.records)
    assert "completed turn dropped" in logged  # 조용히 삼키지 않는다 (T-24)
    for secret in (_QUESTION, *_ANSWER_TOKENS, _CHUNK_TEXT):
        assert secret.strip() not in logged
        assert json.dumps(secret.strip())[1:-1] not in logged
