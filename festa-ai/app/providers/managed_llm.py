"""OpenAI 호환 GMS SSE를 정규화된 LLM token stream과 비용 로그로 변환한다."""

from __future__ import annotations

import json
import logging
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass
from urllib.parse import urljoin

import httpx
from pydantic import SecretStr

from app.providers.llm import LLMRequest, LLMToken


logger = logging.getLogger(__name__)


class ManagedLLMError(RuntimeError):
    """Provider 원문, prompt, Secret을 포함하지 않는 정규화 오류다."""

    def __init__(
        self,
        code: str,
        *,
        retryable: bool,
        status_code: int | None = None,
    ) -> None:
        message = code
        if status_code is not None:
            message += f" (status={status_code})"
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class _Usage:
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


class ManagedLLMProvider:
    """GMS Chat Completions streaming 호출과 응답 정규화를 담당한다."""

    def __init__(
        self,
        *,
        api_base_url: str,
        api_path: str,
        api_key: SecretStr,
        model_id: str,
        temperature: float,
        connect_timeout_seconds: float = 10.0,
        read_timeout_seconds: float = 15.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.model_id = model_id
        self._api_url = urljoin(
            f"{api_base_url.rstrip('/')}/", api_path.lstrip("/")
        )
        self._api_key = api_key
        self._temperature = temperature
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(
                read_timeout_seconds,
                connect=connect_timeout_seconds,
            )
        )

    async def stream(self, request: LLMRequest) -> AsyncIterator[LLMToken]:
        started = time.perf_counter()
        usage: _Usage | None = None
        try:
            async with self._client.stream(
                "POST",
                self._api_url,
                headers={
                    "Authorization": f"Bearer {self._api_key.get_secret_value()}"
                },
                json={
                    "model": self.model_id,
                    "messages": [
                        {"role": message.role, "content": message.content}
                        for message in request.messages
                    ],
                    "temperature": self._temperature,
                    "max_completion_tokens": request.max_output_tokens,
                    "stream": True,
                    "stream_options": {"include_usage": True},
                },
            ) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    event = self._parse_sse_line(line)
                    if event is None:
                        continue
                    if event == "[DONE]":
                        break
                    if not isinstance(event, dict):
                        raise ManagedLLMError(
                            "LLM_INVALID_RESPONSE", retryable=False
                        )
                    parsed_usage = self._parse_usage(event.get("usage"))
                    if parsed_usage is not None:
                        usage = parsed_usage
                    for text in self._parse_deltas(event):
                        yield LLMToken(text=text)
        except ManagedLLMError:
            raise
        except httpx.TimeoutException as exc:
            raise ManagedLLMError("LLM_TIMEOUT", retryable=True) from exc
        except httpx.HTTPStatusError as exc:
            status_code = exc.response.status_code
            raise ManagedLLMError(
                "LLM_PROVIDER_ERROR",
                retryable=status_code == 429 or status_code >= 500,
                status_code=status_code,
            ) from exc
        except httpx.RequestError as exc:
            raise ManagedLLMError("LLM_PROVIDER_ERROR", retryable=True) from exc

        elapsed_ms = round((time.perf_counter() - started) * 1_000, 2)
        if usage is None:
            logger.warning(
                "gms_llm_usage_unavailable model_id=%s request_count=1 elapsed_ms=%s",
                self.model_id,
                elapsed_ms,
            )
        else:
            logger.info(
                "gms_llm_usage model_id=%s request_count=1 prompt_tokens=%d "
                "completion_tokens=%d total_tokens=%d elapsed_ms=%s",
                self.model_id,
                usage.prompt_tokens,
                usage.completion_tokens,
                usage.total_tokens,
                elapsed_ms,
            )

    async def aclose(self) -> None:
        """직접 생성한 HTTP client만 닫는다."""
        if self._owns_client:
            await self._client.aclose()

    @staticmethod
    def _parse_sse_line(line: str) -> object | None:
        stripped = line.strip()
        if not stripped or stripped.startswith(":"):
            return None
        if not stripped.startswith("data:"):
            return None
        payload = stripped.removeprefix("data:").strip()
        if payload == "[DONE]":
            return payload
        try:
            return json.loads(payload)
        except (json.JSONDecodeError, TypeError) as exc:
            raise ManagedLLMError("LLM_INVALID_RESPONSE", retryable=False) from exc

    @staticmethod
    def _parse_deltas(event: dict[str, object]) -> tuple[str, ...]:
        choices = event.get("choices", [])
        if not isinstance(choices, list):
            raise ManagedLLMError("LLM_INVALID_RESPONSE", retryable=False)
        deltas: list[str] = []
        for choice in choices:
            if not isinstance(choice, dict):
                raise ManagedLLMError("LLM_INVALID_RESPONSE", retryable=False)
            delta = choice.get("delta")
            if not isinstance(delta, dict):
                continue
            content = delta.get("content")
            if content is None:
                continue
            if not isinstance(content, str):
                raise ManagedLLMError("LLM_INVALID_RESPONSE", retryable=False)
            if content:
                deltas.append(content)
        return tuple(deltas)

    @staticmethod
    def _parse_usage(value: object) -> _Usage | None:
        if value is None:
            return None
        if not isinstance(value, dict):
            raise ManagedLLMError("LLM_INVALID_RESPONSE", retryable=False)
        names = ("prompt_tokens", "completion_tokens", "total_tokens")
        values = tuple(value.get(name) for name in names)
        if any(
            isinstance(item, bool) or not isinstance(item, int) or item < 0
            for item in values
        ):
            raise ManagedLLMError("LLM_INVALID_RESPONSE", retryable=False)
        return _Usage(*values)
