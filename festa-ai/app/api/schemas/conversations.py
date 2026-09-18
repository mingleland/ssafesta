"""Conversation API payloads implementing spec 008's `conversation-api.yaml`."""

from __future__ import annotations

from datetime import datetime

from pydantic import Field

from app.api.schemas.documents import ApiModel


class CreateConversationRequest(ApiModel):
    booth_id: int = Field(json_schema_extra={"format": "int64"})
    agent_id: int = Field(json_schema_extra={"format": "int64"})


class ConversationResponse(ApiModel):
    conversation_id: str
    expires_at: datetime


class MessageRequest(ApiModel):
    question: str = Field(min_length=1, max_length=2_000)


class HandoffSummaryResponse(ApiModel):
    """S15P21A604-139 응답. D11 — 대화 원문(question/answer)은 절대 담지 않는다."""

    conversation_id: str
    summary: str
    topics: list[str]
    last_user_intent: str
