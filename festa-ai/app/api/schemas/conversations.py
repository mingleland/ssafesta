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
