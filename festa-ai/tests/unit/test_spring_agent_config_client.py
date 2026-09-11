"""Verify the Spring Agent config client is strict, scoped, and fail closed."""

from __future__ import annotations

import httpx
import pytest

from app.clients.spring_agent_config import (
    AgentConfigDenied,
    SpringAgentConfigClient,
    SpringAgentConfigUnavailable,
)
from app.services.context_service import AgentPromptConfig, ProjectFacts


def _client(handler, *, timeout_seconds: float = 1.0) -> SpringAgentConfigClient:
    return SpringAgentConfigClient(
        base_url="http://spring.internal:8080",
        service_token="ai-to-spring-token-1",
        timeout_seconds=timeout_seconds,
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )


@pytest.mark.asyncio
async def test_get_sends_scope_token_and_maps_all_prompt_fields() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        assert request.method == "GET"
        assert request.url.path == "/internal/ai/agent-config"
        assert dict(request.url.params) == {"boothId": "7", "agentId": "3"}
        assert request.headers["Authorization"] == "Bearer ai-to-spring-token-1"
        return httpx.Response(
            200,
            json={
                "found": True,
                "role": "PROJECT_DOCENT",
                "tone": "PROFESSIONAL",
                "responseLength": "LONG",
                "systemPrompt": "프로젝트의 기술 선택을 설명한다.",
                "forbiddenTopics": ["개인정보", "미공개 정보"],
            },
        )

    result = await _client(handler).get(booth_id=7, agent_id=3)

    assert calls == 1
    assert result == AgentPromptConfig(
        role="PROJECT_DOCENT",
        tone="PROFESSIONAL",
        response_length="LONG",
        system_prompt="프로젝트의 기술 선택을 설명한다.",
        forbidden_topics=("개인정보", "미공개 정보"),
    )


@pytest.mark.asyncio
async def test_get_maps_optional_project_facts_without_breaking_legacy_shape() -> None:
    client = _client(
        lambda _request: httpx.Response(
            200,
            json={
                "found": True,
                "role": "PROJECT_DOCENT",
                "tone": "FRIENDLY",
                "responseLength": "SHORT",
                "systemPrompt": "안내한다.",
                "forbiddenTopics": [],
                "projectFacts": {
                    "introduction": "온라인 프로젝트 전시 플랫폼입니다.",
                    "targetAudience": "프로젝트를 전시하고 싶은 교육생",
                    "techStack": "FastAPI, Spring Boot, React, Unity",
                },
            },
        )
    )

    result = await client.get(booth_id=7, agent_id=3)

    assert result.project_facts == ProjectFacts(
        introduction="온라인 프로젝트 전시 플랫폼입니다.",
        target_audience="프로젝트를 전시하고 싶은 교육생",
        tech_stack="FastAPI, Spring Boot, React, Unity",
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("code", ["AGENT_NOT_IN_BOOTH", "AGENT_INACTIVE"])
async def test_denial_is_an_explicit_domain_error(code: str) -> None:
    client = _client(
        lambda _request: httpx.Response(
            200, json={"found": False, "denialCode": code}
        )
    )

    with pytest.raises(AgentConfigDenied) as exc_info:
        await client.get(booth_id=7, agent_id=3)

    assert exc_info.value.code == code


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [401, 500, 503])
async def test_non_200_status_is_fail_closed_without_response_body(status: int) -> None:
    secret_body = "provider-system-prompt-and-token"
    client = _client(
        lambda _request: httpx.Response(status, text=secret_body)
    )

    with pytest.raises(SpringAgentConfigUnavailable) as exc_info:
        await client.get(booth_id=7, agent_id=3)

    assert secret_body not in str(exc_info.value)


@pytest.mark.asyncio
async def test_timeout_is_fail_closed_and_not_retried() -> None:
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise httpx.TimeoutException("secret upstream detail")

    with pytest.raises(SpringAgentConfigUnavailable) as exc_info:
        await _client(handler).get(booth_id=7, agent_id=3)

    assert calls == 1
    assert "secret upstream detail" not in str(exc_info.value)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "body",
    [
        {"found": True},
        {
            "found": True,
            "role": "UNKNOWN",
            "tone": "FRIENDLY",
            "responseLength": "MEDIUM",
            "systemPrompt": "안내한다.",
            "forbiddenTopics": [],
        },
        {
            "found": True,
            "role": "GUIDE",
            "tone": "FRIENDLY",
            "responseLength": "MEDIUM",
            "systemPrompt": "안내한다.",
            "forbiddenTopics": [],
            "unexpected": "field",
        },
        {"found": False, "denialCode": "UNKNOWN"},
        {"found": False, "denialCode": "AGENT_INACTIVE", "systemPrompt": "leak"},
        {
            "found": True,
            "role": "GUIDE",
            "tone": "FRIENDLY",
            "responseLength": "MEDIUM",
            "systemPrompt": "안내한다.",
            "forbiddenTopics": [],
            "projectFacts": {"introduction": "소개만 있음"},
        },
    ],
)
async def test_contract_violation_is_fail_closed(body: object) -> None:
    client = _client(lambda _request: httpx.Response(200, json=body))

    with pytest.raises(SpringAgentConfigUnavailable):
        await client.get(booth_id=7, agent_id=3)
