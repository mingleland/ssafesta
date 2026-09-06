"""Compare the generated `streamConversationMessage` OpenAPI with the checked-in C-07 contract."""

from __future__ import annotations

import pathlib

import yaml
from fastapi import FastAPI

from app.api.v1.conversations import router

FESTA_AI_ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT_PATH = (
    FESTA_AI_ROOT.parent
    / "specs"
    / "008-ai-conversation-rag"
    / "contracts"
    / "conversation-api.yaml"
)


def test_generated_stream_message_openapi_matches_contract() -> None:
    contract = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    app = FastAPI()
    app.include_router(router, prefix="/ai/v1")
    generated = app.openapi()

    contract_operation = contract["paths"]["/conversations/{conversationId}/messages"]["post"]
    generated_operation = generated["paths"][
        "/ai/v1/conversations/{conversationId}/messages"
    ]["post"]

    assert generated_operation["operationId"] == contract_operation["operationId"]
    assert generated_operation["summary"] == contract_operation["summary"]
    assert "text/event-stream" in generated_operation["responses"]["200"]["content"]
    assert {"200", "403", "404"} <= set(generated_operation["responses"])


def test_sse_envelope_schema_declares_c07_required_fields() -> None:
    contract = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    envelope = contract["components"]["schemas"]["SseEnvelope"]

    assert set(envelope["required"]) == {
        "type",
        "requestId",
        "conversationId",
        "messageId",
        "sequence",
    }
    assert envelope["properties"]["type"]["enum"] == [
        "start",
        "token",
        "source",
        "done",
        "error",
    ]
