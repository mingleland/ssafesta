"""Redis-backed Conversation storage — the only place Conversation text lives.

30-minute TTL on every write (data-model.md invariant 3: every Conversation
key must be gone within 30 minutes of the last activity, or immediately on
explicit close — close is 127's scope, not this repository's).
"""

from __future__ import annotations

import json
from datetime import datetime

from redis.asyncio import Redis

from app.models.conversation import Conversation, ConversationScope


class ConversationRepository:
    def __init__(self, redis: Redis, *, ttl_seconds: int) -> None:
        self._redis = redis
        self._ttl_seconds = ttl_seconds

    async def save(self, conversation: Conversation) -> None:
        await self._redis.set(
            self._key(conversation.conversation_id),
            json.dumps(self._to_dict(conversation)),
            ex=self._ttl_seconds,
        )

    async def get(self, conversation_id: str) -> Conversation | None:
        raw = await self._redis.get(self._key(conversation_id))
        if raw is None:
            return None
        return self._from_dict(json.loads(raw))

    @staticmethod
    def _key(conversation_id: str) -> str:
        return f"conversation:{conversation_id}"

    @staticmethod
    def _to_dict(conversation: Conversation) -> dict[str, object]:
        return {
            "conversationId": conversation.conversation_id,
            "userId": conversation.user_id,
            "boothId": conversation.scope.booth_id,
            "agentId": conversation.scope.agent_id,
            "leaseEndsAt": conversation.lease_ends_at.isoformat(),
            "status": conversation.status,
            "lastActivityAt": conversation.last_activity_at.isoformat(),
            "expiresAt": conversation.expires_at.isoformat(),
        }

    @staticmethod
    def _from_dict(data: dict[str, object]) -> Conversation:
        return Conversation(
            conversation_id=data["conversationId"],
            user_id=data["userId"],
            scope=ConversationScope(booth_id=data["boothId"], agent_id=data["agentId"]),
            lease_ends_at=datetime.fromisoformat(data["leaseEndsAt"]),
            status=data["status"],
            last_activity_at=datetime.fromisoformat(data["lastActivityAt"]),
            expires_at=datetime.fromisoformat(data["expiresAt"]),
        )
