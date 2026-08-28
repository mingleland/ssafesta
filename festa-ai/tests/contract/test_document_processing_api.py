"""Compare the generated document-intake OpenAPI with the checked-in contract."""

from __future__ import annotations

import pathlib
from typing import Any

import yaml
from fastapi import FastAPI

from app.api.v1.documents import router

FESTA_AI_ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT_PATH = (
    FESTA_AI_ROOT.parent
    / "specs"
    / "007-ai-agent-document"
    / "contracts"
    / "document-processing-api.yaml"
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


def test_generated_document_process_openapi_matches_contract() -> None:
    contract = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    app = FastAPI()
    app.include_router(router, prefix="/ai/v1")
    generated = app.openapi()

    contract_operation = contract["paths"]["/documents/process"]["post"]
    generated_operation = generated["paths"]["/ai/v1/documents/process"]["post"]

    assert generated_operation["operationId"] == contract_operation["operationId"]
    assert generated_operation["summary"] == contract_operation["summary"]
    assert generated_operation["description"] == contract_operation["description"]
    assert generated_operation["security"] == contract["security"]
    assert set(generated_operation["responses"]) == set(contract_operation["responses"])

    assert _canonical_schema(
        generated["components"]["securitySchemes"]["serviceToken"], {}
    ) == _canonical_schema(contract["components"]["securitySchemes"]["serviceToken"], {})

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

    for response_code, contract_response in contract_operation["responses"].items():
        generated_response = generated_operation["responses"][response_code]
        assert generated_response["description"] == contract_response["description"]
        contract_content = contract_response.get("content")
        if contract_content is None:
            assert "content" not in generated_response
            continue
        contract_schema = contract_content["application/json"]["schema"]
        generated_schema = generated_response["content"]["application/json"]["schema"]
        assert _canonical_schema(
            generated_schema, generated_components
        ) == _canonical_schema(contract_schema, contract_components)
