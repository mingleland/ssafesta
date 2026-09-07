"""Stream one Conversation message as a C-07 SSE envelope sequence (spec 008 FR-005a).

Terminal exclusivity: exactly one of `done`/`error` is emitted per message,
never both, and nothing follows it. Ownership/lease/not-found authorization
happens before the generator starts (`authorize()`), so anything that fails
once streaming begins surfaces as an `error` event, never an HTTP error —
"SSE 연결 전에 판정한 오류는 HTTP 오류로, 연결 후 오류는 `error` 이벤트로" (spec 008).
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from collections.abc import AsyncIterator, Callable
from datetime import datetime, timezone

from app.clients.spring_agent_config import (
    AgentConfigDenied,
    SpringAgentConfigUnavailable,
)
from app.models.conversation import (
    Conversation,
    ConversationTurn,
    SourceCitation,
)
from app.providers.llm import LLMProvider
from app.providers.managed_llm import ManagedLLMError
from app.repositories.conversation_repository import ConversationRepository
from app.services.rag_service import NoReadyContextResult, RagContextService

logger = logging.getLogger(__name__)


class ConversationNotFound(Exception):
    pass


class ConversationOwnershipMismatch(Exception):
    pass


class BoothLeaseExpired(Exception):
    pass


class _StreamTimeout(Exception):
    """FR-007 — TTFT 또는 전체 응답 timeout 예산을 넘겼다."""

    def __init__(self, phase: str) -> None:
        super().__init__(phase)
        self.phase = phase


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def _default_clock() -> datetime:
    return datetime.now(timezone.utc)


class ConversationStreamService:
    def __init__(
        self,
        *,
        repository: ConversationRepository,
        rag_context_service: RagContextService,
        llm_provider: LLMProvider,
        ttl_seconds: int,
        clock: Callable[[], datetime] = _default_clock,
        ttft_timeout_seconds: float = 15.0,
        total_timeout_seconds: float = 60.0,
    ) -> None:
        self._repository = repository
        self._rag_context_service = rag_context_service
        self._llm_provider = llm_provider
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._ttft_timeout_seconds = ttft_timeout_seconds
        self._total_timeout_seconds = total_timeout_seconds

    async def authorize(self, *, conversation_id: str, user_id: int) -> Conversation:
        """Run every check that must fail as an HTTP error, before SSE starts."""
        conversation = await self._repository.get(conversation_id)
        if conversation is None:
            raise ConversationNotFound(conversation_id)
        if conversation.user_id != user_id:
            raise ConversationOwnershipMismatch(conversation_id)
        if conversation.lease_ends_at <= self._clock():
            raise BoothLeaseExpired(conversation_id)
        return conversation

    async def stream(
        self, *, conversation: Conversation, question: str
    ) -> AsyncIterator[str]:
        request_id = _new_id("req")
        user_message_id = _new_id("umsg")
        assistant_message_id = _new_id("amsg")
        sequence = 0

        def render(event_type: str, fields: dict[str, object]) -> str:
            nonlocal sequence
            data = {
                "type": event_type,
                "requestId": request_id,
                "conversationId": conversation.conversation_id,
                "messageId": assistant_message_id,
                "sequence": sequence,
                **fields,
            }
            sequence += 1
            return f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

        yield render("start", {})

        try:
            context = await self._rag_context_service.build(
                conversation=conversation, question=question
            )
        except AgentConfigDenied as exc:
            logger.warning(
                "Agent config denied for conversation %s code=%s",
                conversation.conversation_id,
                exc.code,
            )
            yield render(
                "error",
                {
                    "code": exc.code,
                    "message": "AI 직원 설정을 사용할 수 없습니다.",
                    "retryable": False,
                },
            )
            return
        except SpringAgentConfigUnavailable:
            logger.warning(
                "Agent config lookup failed for conversation %s",
                conversation.conversation_id,
            )
            yield render(
                "error",
                {
                    "code": "AGENT_CONFIG_UNAVAILABLE",
                    "message": "AI 직원 설정을 확인하지 못했습니다.",
                    "retryable": True,
                },
            )
            return
        except Exception:
            logger.exception(
                "RAG context build failed for conversation %s", conversation.conversation_id
            )
            yield render(
                "error",
                {
                    "code": "CONTEXT_BUILD_FAILED",
                    "message": "답변을 준비하지 못했습니다.",
                    "retryable": True,
                },
            )
            return

        if isinstance(context, NoReadyContextResult):
            yield render("token", {"delta": context.message})
            yield render("done", {"handoffRecommended": False})
            now = self._clock()
            turn = ConversationTurn(
                request_id=request_id,
                user_message_id=user_message_id,
                assistant_message_id=assistant_message_id,
                question=question,
                answer=context.message,
                sources=(),
                created_at=now,
            )
            updated = conversation.record_turn(turn, now=now, ttl_seconds=self._ttl_seconds)
            await self._repository.save(updated)
            return

        answer_parts: list[str] = []
        started_at = time.monotonic()
        got_first_token = False
        agen = self._llm_provider.stream(context.request).__aiter__()
        try:
            while True:
                if got_first_token:
                    deadline = self._total_timeout_seconds
                else:
                    deadline = min(self._ttft_timeout_seconds, self._total_timeout_seconds)
                remaining = deadline - (time.monotonic() - started_at)
                phase = "TOTAL_RESPONSE" if got_first_token else "FIRST_TOKEN"
                if remaining <= 0:
                    raise _StreamTimeout(phase)
                try:
                    token = await asyncio.wait_for(agen.__anext__(), timeout=remaining)
                except TimeoutError as exc:
                    raise _StreamTimeout(phase) from exc
                except StopAsyncIteration:
                    break
                if not token.text:
                    continue
                got_first_token = True
                answer_parts.append(token.text)
                yield render("token", {"delta": token.text})
        except _StreamTimeout as exc:
            logger.warning(
                "LLM stream timed out for conversation %s phase=%s",
                conversation.conversation_id,
                exc.phase,
            )
            yield render(
                "error",
                {
                    "code": "LLM_TIMEOUT",
                    "message": "AI 응답 생성이 지연되고 있습니다.",
                    "retryable": True,
                    "timeoutPhase": exc.phase,
                },
            )
            return
        except ManagedLLMError as exc:
            logger.exception(
                "LLM stream failed for conversation %s", conversation.conversation_id
            )
            yield render(
                "error",
                {"code": exc.code, "message": "AI 응답 생성에 실패했습니다.", "retryable": exc.retryable},
            )
            return
        except Exception:
            logger.exception(
                "Unexpected LLM stream failure for conversation %s",
                conversation.conversation_id,
            )
            yield render(
                "error",
                {
                    "code": "LLM_STREAM_FAILED",
                    "message": "AI 응답 생성에 실패했습니다.",
                    "retryable": True,
                },
            )
            return

        seen: set[tuple[int, int]] = set()
        citations: list[SourceCitation] = []
        for chunk in context.included_chunks:
            key = (chunk.document_id, chunk.chunk_id)
            if key in seen:
                continue
            seen.add(key)
            citations.append(
                SourceCitation(
                    document_id=chunk.document_id,
                    chunk_id=chunk.chunk_id,
                    title=chunk.original_filename,
                )
            )
            yield render(
                "source",
                {
                    "documentId": chunk.document_id,
                    "chunkId": chunk.chunk_id,
                    "title": chunk.original_filename,
                },
            )

        yield render("done", {"handoffRecommended": False})

        answer = "".join(answer_parts).strip()
        if not answer:
            return

        now = self._clock()
        turn = ConversationTurn(
            request_id=request_id,
            user_message_id=user_message_id,
            assistant_message_id=assistant_message_id,
            question=question,
            answer=answer,
            sources=tuple(citations),
            created_at=now,
        )
        updated = conversation.record_turn(turn, now=now, ttl_seconds=self._ttl_seconds)
        await self._repository.save(updated)
