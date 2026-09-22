"""Conversation creation — Fail Closed on anything but an explicit Spring allow.

FR-024/FR-025: any outcome other than a parsed `allowed:true` (denial or
Spring being unreachable after the client's own retry) must refuse to create
the Conversation and must not touch retrieval or the LLM.

Creation and explicit close live here; the idle TTL belongs to the
repository, which puts it on every write.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Callable
from datetime import datetime, timezone

from app.clients.spring_booth_access import SpringBoothAccessUnavailable
from app.clients.spring_mission import SpringMissionMarkerUnavailable
from app.core.logging import log_event
from app.models.conversation import Conversation
from app.repositories.conversation_repository import ConversationRepository
from app.services.stream_service import ConversationOwnershipMismatch


class BoothAccessDenied(Exception):
    """Spring answered but refused — Lease invalid, Agent not in Booth, or INACTIVE."""

    def __init__(self, denial_code: str) -> None:
        super().__init__(denial_code)
        self.denial_code = denial_code


class ConversationCreationFailed(Exception):
    """Spring did not answer in time — Fail Closed per FR-025."""


logger = logging.getLogger(__name__)


def _default_id_factory() -> str:
    return f"conv_{uuid.uuid4().hex}"


def _default_clock() -> datetime:
    return datetime.now(timezone.utc)


class ConversationService:
    def __init__(
        self,
        *,
        spring_client,
        mission_client,
        repository: ConversationRepository,
        ttl_seconds: int,
        clock: Callable[[], datetime] = _default_clock,
        id_factory: Callable[[], str] = _default_id_factory,
    ) -> None:
        self._spring_client = spring_client
        self._mission_client = mission_client
        self._repository = repository
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._id_factory = id_factory

    async def create(self, *, user_id: int, booth_id: int, agent_id: int) -> Conversation:
        try:
            result = await self._spring_client.check(booth_id=booth_id, agent_id=agent_id)
        except SpringBoothAccessUnavailable as exc:
            raise ConversationCreationFailed(
                "Spring booth-access validation unavailable"
            ) from exc

        if not result.allowed:
            assert result.denial_code is not None
            raise BoothAccessDenied(result.denial_code)

        assert result.lease_ends_at is not None
        conversation = Conversation.create(
            conversation_id=self._id_factory(),
            user_id=user_id,
            booth_id=booth_id,
            agent_id=agent_id,
            lease_ends_at=datetime.fromisoformat(result.lease_ends_at),
            now=self._clock(),
            ttl_seconds=self._ttl_seconds,
        )
        await self._repository.save(conversation)
        # 일일 미션 AI_CONSULT 의 유일한 사실 근거다 (spec 022 FR-003a). Booth Access 와 달리
        # Fail Closed 가 아니다 — 마커 하나 때문에 대화를 막지 않는다 (FR-004a). 클라이언트가
        # 던지는 예외를 하나로 모아 두었으므로 여기서 넓은 except 를 쓸 이유가 없다.
        try:
            await self._mission_client.mark_ai_consult(user_id=user_id)
        except SpringMissionMarkerUnavailable:
            log_event(
                logger,
                logging.WARNING,
                "ai_consult_marker_undelivered",
                conversation_id=conversation.conversation_id,
                status="UNDELIVERED",
                error_code="SPRING_MISSION_MARKER_UNAVAILABLE",
            )
        return conversation

    async def close(self, *, conversation_id: str, user_id: int) -> None:
        """Delete the raw text now (FR-014/FR-028, D11).

        Idempotent: an unknown or already-expired id succeeds, so a retried
        or duplicated close is harmless. Deliberately does not reuse the
        streaming `authorize()` — an expired Lease must still be able to
        delete, and no Spring call belongs on this path (Article 3, AI
        failure isolation).
        """
        conversation = await self._repository.get(conversation_id)
        if conversation is None:
            return
        if conversation.user_id != user_id:
            raise ConversationOwnershipMismatch(conversation_id)
        await self._repository.delete(conversation_id)
