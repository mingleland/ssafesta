"""Lock the FastAPI client vocabulary to the Spring Agent config OpenAPI contract."""

from __future__ import annotations

from pathlib import Path

import yaml


def test_agent_config_contract_path_fields_and_denials() -> None:
    contract_path = (
        Path(__file__).parents[3]
        / "specs"
        / "008-ai-conversation-rag"
        / "contracts"
        / "spring-agent-config-api.yaml"
    )
    contract = yaml.safe_load(contract_path.read_text(encoding="utf-8"))
    operation = contract["paths"]["/agent-config"]["get"]
    parameters = {item["name"] for item in operation["parameters"]}
    schemas = contract["components"]["schemas"]

    assert parameters == {"boothId", "agentId"}
    assert operation["responses"].keys() >= {"200", "401"}
    assert set(schemas["AgentConfigFound"]["required"]) == {
        "found",
        "role",
        "tone",
        "responseLength",
        "systemPrompt",
        "forbiddenTopics",
    }
    assert schemas["AgentConfigAgentNotInBooth"]["properties"]["denialCode"]["const"] == "AGENT_NOT_IN_BOOTH"
    assert schemas["AgentConfigAgentInactive"]["properties"]["denialCode"]["const"] == "AGENT_INACTIVE"
