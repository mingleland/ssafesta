"""Redis-backed Conversation storage — the only place Conversation text lives.

30-minute TTL on every write (data-model.md invariant 3: every Conversation
key must be gone within 30 minutes of the last activity, or immediately on
explicit close).

`save` creates, `commit_turn` updates only what is still alive, `delete`
removes. The split matters: a completed turn must never resurrect a
Conversation that an explicit close or the idle TTL already removed
(plan.md §5 "원자적으로 확정").
"""

from __future__ import annotations

import json
from datetime import datetime

from redis.asyncio import Redis

from app.models.conversation import (
    Conversation,
    ConversationScope,
    ConversationTurn,
    SourceCitation,
)


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

    async def commit_turn(self, conversation: Conversation) -> bool:
        """Persist a completed turn only while the Conversation still exists.

        `SET ... XX` fails when an explicit close or the idle TTL removed the
        key mid-stream, so the raw text stays gone instead of coming back with
        a fresh 30-minute lifetime. The return value is that outcome — callers
        must not discard it silently.
        """
        stored = await self._redis.set(
            self._key(conversation.conversation_id),
            json.dumps(self._to_dict(conversation)),
            ex=self._ttl_seconds,
            xx=True,
        )
        return bool(stored)

    async def delete(self, conversation_id: str) -> None:
        """Drop the single key holding this Conversation and its turn text."""
        await self._redis.delete(self._key(conversation_id))

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
            "turns": [
                {
                    "requestId": turn.request_id,
                    "userMessageId": turn.user_message_id,
                    "assistantMessageId": turn.assistant_message_id,
                    "question": turn.question,
                    "answer": turn.answer,
                    "sources": [
                        {
                            "documentId": source.document_id,
                            "chunkId": source.chunk_id,
                            "title": source.title,
                        }
                        for source in turn.sources
                    ],
                    "createdAt": turn.created_at.isoformat(),
                }
                for turn in conversation.turns
            ],
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
            turns=tuple(
                ConversationTurn(
                    request_id=turn["requestId"],
                    user_message_id=turn["userMessageId"],
                    assistant_message_id=turn["assistantMessageId"],
                    question=turn["question"],
                    answer=turn["answer"],
                    sources=tuple(
                        SourceCitation(
                            document_id=source["documentId"],
                            chunk_id=source["chunkId"],
                            title=source["title"],
                        )
                        for source in turn["sources"]
                    ),
                    created_at=datetime.fromisoformat(turn["createdAt"]),
                )
                for turn in data.get("turns", [])
            ),
        )
