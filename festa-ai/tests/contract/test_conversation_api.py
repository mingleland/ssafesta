"""Compare the generated Conversation-create OpenAPI with the checked-in contract.

Only `createConversation` — `close`/`streamConversationMessage` in
`conversation-api.yaml` belong to 127/140 and are not implemented yet.
"""

from __future__ import annotations

import pathlib
from typing import Any

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


def _canonical_schema(schema: dict[str, Any], components: dict[str, Any]) -> Any:
    if "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        return _canonical_schema(components[name], components)

    ignored = {"title", "description"}
    return {
        key: (
            _canonical_schema(value, components)
            if isinstance(value, dict)
            else [
                _canonical_schema(item, components) if isinstance(item, dict) else item
                for item in value
            ]
            if isinstance(value, list)
            else value
        )
        for key, value in schema.items()
        if key not in ignored
    }


def test_generated_create_conversation_openapi_matches_contract() -> None:
    contract = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    app = FastAPI()
    app.include_router(router, prefix="/ai/v1")
    generated = app.openapi()

    contract_operation = contract["paths"]["/conversations"]["post"]
    generated_operation = generated["paths"]["/ai/v1/conversations"]["post"]

    assert generated_operation["operationId"] == contract_operation["operationId"]
    assert generated_operation["summary"] == contract_operation["summary"]
    assert generated_operation["security"] == contract["security"]
    assert {"201", "401", "403", "503"} <= set(generated_operation["responses"])

    assert _canonical_schema(
        generated["components"]["securitySchemes"]["userAccessToken"], {}
    ) == _canonical_schema(
        contract["components"]["securitySchemes"]["userAccessToken"], {}
    )

    contract_components = contract["components"]["schemas"]
    generated_components = generated["components"]["schemas"]

    contract_request = contract_operation["requestBody"]["content"]["application/json"][
        "schema"
    ]
    generated_request = generated_operation["requestBody"]["content"]["application/json"][
        "schema"
    ]
    assert _canonical_schema(generated_request, generated_components) == _canonical_schema(
        contract_request, contract_components
    )

    contract_response = contract_operation["responses"]["201"]["content"][
        "application/json"
    ]["schema"]
    generated_response = generated_operation["responses"]["201"]["content"][
        "application/json"
    ]["schema"]
    assert _canonical_schema(generated_response, generated_components) == _canonical_schema(
        contract_response, contract_components
    )
