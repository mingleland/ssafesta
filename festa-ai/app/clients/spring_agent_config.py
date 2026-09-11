"""Load one Agent's prompt configuration from Spring before RAG retrieval.

Spring owns ``ai_agents`` and exposes the closed contract at
``GET /internal/ai/agent-config``. This client deliberately performs one
request per question and keeps no cache so an inactive or moved Agent is
rejected before Embedding, chunk search, or LLM work begins.
"""

from __future__ import annotations

from collections.abc import Mapping

import httpx

from app.services.context_service import (
    AgentPromptConfig,
    ProjectFacts,
    RESPONSE_LENGTH_INSTRUCTIONS,
    ROLE_INSTRUCTIONS,
    TONE_INSTRUCTIONS,
)

_FOUND_FIELDS = {
    "found",
    "role",
    "tone",
    "responseLength",
    "systemPrompt",
    "forbiddenTopics",
}
_FOUND_FIELDS_WITH_PROJECT_FACTS = _FOUND_FIELDS | {"projectFacts"}
_DENIED_FIELDS = {"found", "denialCode"}
_DENIAL_CODES = {"AGENT_NOT_IN_BOOTH", "AGENT_INACTIVE"}


class SpringAgentConfigUnavailable(RuntimeError):
    """Spring failed or returned data outside the closed OpenAPI contract."""


class AgentConfigDenied(RuntimeError):
    """Spring explicitly refused use of this Agent for the requested Booth."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class SpringAgentConfigClient:
    """Resolve Agent prompt settings through the shared Spring HTTP client."""

    def __init__(
        self,
        *,
        base_url: str,
        service_token: str,
        timeout_seconds: float,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._service_token = service_token
        self._timeout_seconds = timeout_seconds
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_seconds, connect=timeout_seconds)
        )

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def get(self, *, booth_id: int, agent_id: int) -> AgentPromptConfig:
        try:
            response = await self._client.get(
                f"{self._base_url}/internal/ai/agent-config",
                params={"boothId": booth_id, "agentId": agent_id},
                headers={"Authorization": f"Bearer {self._service_token}"},
                timeout=httpx.Timeout(
                    self._timeout_seconds, connect=self._timeout_seconds
                ),
            )
        except httpx.HTTPError as exc:
            raise SpringAgentConfigUnavailable(
                "Spring agent-config request failed"
            ) from exc

        if response.status_code != 200:
            raise SpringAgentConfigUnavailable(
                f"Spring agent-config returned status {response.status_code}"
            )
        try:
            body = response.json()
        except ValueError as exc:
            raise SpringAgentConfigUnavailable(
                "Spring agent-config response is not valid JSON"
            ) from exc
        return self._parse(body)

    @staticmethod
    def _parse(body: object) -> AgentPromptConfig:
        if not isinstance(body, Mapping) or type(body.get("found")) is not bool:
            raise SpringAgentConfigUnavailable(
                "Spring agent-config response is not a valid object"
            )

        if body["found"] is False:
            if set(body) != _DENIED_FIELDS:
                raise SpringAgentConfigUnavailable(
                    "Spring agent-config denial shape is invalid"
                )
            denial_code = body["denialCode"]
            if type(denial_code) is not str or denial_code not in _DENIAL_CODES:
                raise SpringAgentConfigUnavailable(
                    "Spring agent-config denial code is invalid"
                )
            raise AgentConfigDenied(denial_code)

        if set(body) not in (_FOUND_FIELDS, _FOUND_FIELDS_WITH_PROJECT_FACTS):
            raise SpringAgentConfigUnavailable(
                "Spring agent-config success shape is invalid"
            )

        role = body["role"]
        tone = body["tone"]
        response_length = body["responseLength"]
        system_prompt = body["systemPrompt"]
        forbidden_topics = body["forbiddenTopics"]
        project_facts = _parse_project_facts(body.get("projectFacts"))
        if type(role) is not str or role not in ROLE_INSTRUCTIONS:
            raise SpringAgentConfigUnavailable("Spring agent-config role is invalid")
        if type(tone) is not str or tone not in TONE_INSTRUCTIONS:
            raise SpringAgentConfigUnavailable("Spring agent-config tone is invalid")
        if (
            type(response_length) is not str
            or response_length not in RESPONSE_LENGTH_INSTRUCTIONS
        ):
            raise SpringAgentConfigUnavailable(
                "Spring agent-config responseLength is invalid"
            )
        if type(system_prompt) is not str or not system_prompt.strip():
            raise SpringAgentConfigUnavailable(
                "Spring agent-config systemPrompt is invalid"
            )
        if not isinstance(forbidden_topics, list) or any(
            type(topic) is not str or not topic.strip() for topic in forbidden_topics
        ):
            raise SpringAgentConfigUnavailable(
                "Spring agent-config forbiddenTopics is invalid"
            )

        return AgentPromptConfig(
            role=role,
            tone=tone,
            response_length=response_length,
            system_prompt=system_prompt,
            forbidden_topics=tuple(forbidden_topics),
            project_facts=project_facts,
        )


def _parse_project_facts(value: object) -> ProjectFacts | None:
    if value is None:
        return None
    if not isinstance(value, Mapping) or set(value) != {
        "introduction",
        "targetAudience",
        "techStack",
    }:
        raise SpringAgentConfigUnavailable(
            "Spring agent-config projectFacts shape is invalid"
        )
    facts = (value["introduction"], value["targetAudience"], value["techStack"])
    if any(
        item is not None and (type(item) is not str or not item.strip())
        for item in facts
    ):
        raise SpringAgentConfigUnavailable(
            "Spring agent-config projectFacts value is invalid"
        )
    try:
        return ProjectFacts(
            introduction=value["introduction"],
            target_audience=value["targetAudience"],
            tech_stack=value["techStack"],
        )
    except ValueError as exc:
        raise SpringAgentConfigUnavailable(
            "Spring agent-config projectFacts value is invalid"
        ) from exc
