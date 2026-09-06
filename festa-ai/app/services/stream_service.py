"""Stream one Conversation message as a C-07 SSE envelope sequence (spec 008 FR-005a).

Terminal exclusivity: exactly one of `done`/`error` is emitted per message,
never both, and nothing follows it. Ownership/lease/not-found authorization
happens before the generator starts (`authorize()`), so anything that fails
once streaming begins surfaces as an `error` event, never an HTTP error —
"SSE 연결 전에 판정한 오류는 HTTP 오류로, 연결 후 오류는 `error` 이벤트로" (spec 008).
"""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import AsyncIterator, Callable
from datetime import datetime, timezone

from app.models.conversation import (
    Conversation,
    ConversationTurn,
    SourceCitation,
)
from app.providers.llm import LLMProvider
from app.providers.managed_llm import ManagedLLMError
from app.repositories.conversation_repository import ConversationRepository
from app.repositories.document_job_repository import DocumentJobRepository
from app.services.rag_service import RagContextService

logger = logging.getLogger(__name__)


class ConversationNotFound(Exception):
    pass


class ConversationOwnershipMismatch(Exception):
    pass


class BoothLeaseExpired(Exception):
    pass


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
        document_job_repository: DocumentJobRepository,
        ttl_seconds: int,
        clock: Callable[[], datetime] = _default_clock,
    ) -> None:
        self._repository = repository
        self._rag_context_service = rag_context_service
        self._llm_provider = llm_provider
        self._document_job_repository = document_job_repository
        self._ttl_seconds = ttl_seconds
        self._clock = clock

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

        answer_parts: list[str] = []
        try:
            async for token in self._llm_provider.stream(context.request):
                if not token.text:
                    continue
                answer_parts.append(token.text)
                yield render("token", {"delta": token.text})
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

        titles = await self._document_job_repository.find_titles(
            [chunk.document_id for chunk in context.included_chunks]
        )
        seen: set[tuple[int, int]] = set()
        citations: list[SourceCitation] = []
        for chunk in context.included_chunks:
            key = (chunk.document_id, chunk.chunk_id)
            if key in seen:
                continue
            seen.add(key)
            title = titles.get(chunk.document_id, f"문서 {chunk.document_id}")
            citations.append(
                SourceCitation(document_id=chunk.document_id, chunk_id=chunk.chunk_id, title=title)
            )
            yield render(
                "source",
                {"documentId": chunk.document_id, "chunkId": chunk.chunk_id, "title": title},
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
