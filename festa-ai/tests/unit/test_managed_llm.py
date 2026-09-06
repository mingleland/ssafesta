"""GMS LLM streaming 응답을 Provider 독립 token과 비용 로그로 변환하는지 검증한다."""

import json
from types import SimpleNamespace
from unittest.mock import patch

import httpx
import pytest
from pydantic import SecretStr

from app.providers import managed_llm
from app.providers.factory import create_llm_provider
from app.providers.llm import LLMMessage, LLMRequest
from app.providers.managed_llm import ManagedLLMError, ManagedLLMProvider
from app.providers.mock import MockLLMProvider


def _provider(client: httpx.AsyncClient) -> ManagedLLMProvider:
    return ManagedLLMProvider(
        api_base_url="https://gms.example.test",
        api_path="/v1/chat/completions",
        api_key=SecretStr("never-log-this-key"),
        model_id="gpt-4.1-mini",
        temperature=0.0,
        client=client,
    )


@pytest.mark.asyncio
async def test_managed_llm_streams_deltas_and_logs_usage(
) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer never-log-this-key"
        payload = json.loads(request.content)
        assert payload == {
            "model": "gpt-4.1-mini",
            "messages": [
                {"role": "system", "content": "secret prompt"},
                {"role": "user", "content": "질문"},
            ],
            "temperature": 0.0,
            "max_completion_tokens": 400,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        body = "\n".join(
            (
                'data: {"choices":[{"delta":{"role":"assistant"}}]}',
                'data: {"choices":[{"delta":{"content":"안녕"}}]}',
                'data: {"choices":[{"delta":{"content":"하세요"}}]}',
                'data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}',
                "data: [DONE]",
                "",
            )
        )
        return httpx.Response(200, text=body)

    request = LLMRequest(
        messages=(
            LLMMessage(role="system", content="secret prompt"),
            LLMMessage(role="user", content="질문"),
        ),
        max_output_tokens=400,
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with patch.object(managed_llm.logger, "info") as log_usage:
            tokens = [token.text async for token in _provider(client).stream(request)]

    assert tokens == ["안녕", "하세요"]
    log_usage.assert_called_once()
    rendered = " ".join(str(value) for value in log_usage.call_args.args)
    assert "gms_llm_usage" in rendered
    assert log_usage.call_args.args[2:5] == (12, 3, 15)
    assert "secret prompt" not in rendered
    assert "never-log-this-key" not in rendered


@pytest.mark.asyncio
async def test_provider_error_does_not_expose_secret_or_raw_body() -> None:
    raw_body = "provider-internal-secret-response"

    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text=raw_body)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ManagedLLMError) as exc_info:
            _ = [
                token
                async for token in _provider(client).stream(
                    LLMRequest(
                        messages=(LLMMessage(role="user", content="secret prompt"),)
                    )
                )
            ]

    rendered = str(exc_info.value)
    assert exc_info.value.code == "LLM_PROVIDER_ERROR"
    assert exc_info.value.retryable is True
    assert exc_info.value.status_code == 500
    assert raw_body not in rendered
    assert "never-log-this-key" not in rendered
    assert "secret prompt" not in rendered


@pytest.mark.asyncio
async def test_invalid_stream_event_is_normalized() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="data: not-json\n\n")

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ManagedLLMError, match="LLM_INVALID_RESPONSE"):
            _ = [
                token
                async for token in _provider(client).stream(
                    LLMRequest(messages=(LLMMessage(role="user", content="질문"),))
                )
            ]


def test_factory_selects_mock_llm_without_managed_settings() -> None:
    provider = create_llm_provider(SimpleNamespace(llm_provider="mock"))  # type: ignore[arg-type]

    assert isinstance(provider, MockLLMProvider)


def test_factory_selects_managed_llm_from_validated_settings() -> None:
    settings = SimpleNamespace(
        llm_provider="gms",
        llm_model_id="gpt-4.1-mini",
        llm_api_base_url="https://gms.example.test",
        llm_api_path="/v1/chat/completions",
        gms_api_key=SecretStr("never-log-this-key"),
        llm_temperature=0.0,
        llm_connect_timeout_seconds=10.0,
        llm_read_timeout_seconds=15.0,
    )

    provider = create_llm_provider(settings)  # type: ignore[arg-type]

    assert isinstance(provider, ManagedLLMProvider)
    assert provider.model_id == "gpt-4.1-mini"
