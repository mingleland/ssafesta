"""GMS의 OpenAI·Gemini Chat Completions REST API를 호출해 텍스트 응답을 얻는다.

실패는 조용히 삼키지 않는다 — 예외를 던지는 대신 ``ChatResult.success``와
``error``에 드러내, 여러 질문·모델을 순회하는 배치가 한 건 실패로 전체 중단되지
않게 한다.
"""

from __future__ import annotations

import time
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Protocol

from .models import ModelSpec


DEFAULT_OPENAI_CHAT_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
DEFAULT_GEMINI_GENERATE_URL_TEMPLATE = (
    "https://gms.ssafy.io/gmsapi/generativelanguage.googleapis.com/"
    "v1beta/models/{model}:generateContent"
)


class JsonPoster(Protocol):
    def __call__(self, payload: dict[str, Any]) -> dict[str, Any]: ...


class PosterFactory(Protocol):
    def __call__(self, model: ModelSpec) -> JsonPoster: ...


@dataclass(frozen=True)
class ChatResult:
    text: str
    elapsed_seconds: float
    request_count: int
    estimated_credits: float
    success: bool
    error: str | None = None


class GmsChatClient:
    def __init__(
        self,
        *,
        api_key: str,
        timeout_seconds: float = 60.0,
        poster_factory: PosterFactory | None = None,
    ) -> None:
        if not api_key.strip():
            raise ValueError("GMS_API_KEY가 비어 있습니다.")
        self._api_key = api_key
        self._timeout_seconds = timeout_seconds
        self._poster_factory = poster_factory or self._default_poster_factory
        self._posters: dict[str, JsonPoster] = {}

    def _default_poster_factory(self, model: ModelSpec) -> JsonPoster:
        if model.provider == "openai":
            url = DEFAULT_OPENAI_CHAT_URL
            headers = {"Authorization": f"Bearer {self._api_key}"}
        elif model.provider == "gemini":
            url = DEFAULT_GEMINI_GENERATE_URL_TEMPLATE.format(model=model.model_id)
            headers = {"x-goog-api-key": self._api_key}
        else:
            raise ValueError(f"지원하지 않는 LLM provider: {model.provider}")
        return _build_http_poster(url=url, headers=headers, timeout_seconds=self._timeout_seconds)

    def _poster_for(self, model: ModelSpec) -> JsonPoster:
        poster = self._posters.get(model.model_id)
        if poster is None:
            poster = self._poster_factory(model)
            self._posters[model.model_id] = poster
        return poster

    def complete(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        model: ModelSpec,
        temperature: float = 0.0,
        max_tokens: int | None = None,
    ) -> ChatResult:
        started = time.perf_counter()
        try:
            poster = self._poster_for(model)
            if model.provider == "openai":
                payload: dict[str, Any] = {
                    "model": model.model_id,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    "temperature": temperature,
                }
                if max_tokens is not None:
                    # gpt-5.x 계열은 max_tokens를 거부하고 max_completion_tokens만 받는다.
                    # 이 파라미터명이 구형 모델(gpt-4.x)에서도 동작함을 실측으로 확인했다.
                    payload["max_completion_tokens"] = max_tokens
                body = poster(payload)
                text = _extract_openai_text(body)
            elif model.provider == "gemini":
                generation_config: dict[str, Any] = {"temperature": temperature}
                if max_tokens is not None:
                    generation_config["maxOutputTokens"] = max_tokens
                body = poster(
                    {
                        "systemInstruction": {"parts": [{"text": system_prompt}]},
                        "contents": [{"parts": [{"text": user_prompt}]}],
                        "generationConfig": generation_config,
                    }
                )
                text = _extract_gemini_text(body)
            else:
                raise ValueError(f"지원하지 않는 LLM provider: {model.provider}")
        except Exception as exc:  # noqa: BLE001 - 외부 LLM 실패를 전부 표면화한다
            return ChatResult(
                text="",
                elapsed_seconds=time.perf_counter() - started,
                request_count=1,
                estimated_credits=model.credit_per_request,
                success=False,
                error=f"GMS Chat 요청 실패({model.model_id}): {exc}",
            )
        return ChatResult(
            text=text,
            elapsed_seconds=time.perf_counter() - started,
            request_count=1,
            estimated_credits=model.credit_per_request,
            success=True,
        )


def _extract_openai_text(body: Mapping[str, Any]) -> str:
    choices = body.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ValueError("OpenAI 응답에 choices가 없습니다.")
    message = choices[0].get("message") if isinstance(choices[0], Mapping) else None
    content = message.get("content") if isinstance(message, Mapping) else None
    if not isinstance(content, str):
        raise ValueError("OpenAI 응답에 message.content가 없습니다.")
    return content


def _extract_gemini_text(body: Mapping[str, Any]) -> str:
    candidates = body.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        raise ValueError("Gemini 응답에 candidates가 없습니다.")
    content = candidates[0].get("content") if isinstance(candidates[0], Mapping) else None
    parts = content.get("parts") if isinstance(content, Mapping) else None
    if not isinstance(parts, list) or not parts:
        raise ValueError("Gemini 응답에 content.parts가 없습니다.")
    text = parts[0].get("text") if isinstance(parts[0], Mapping) else None
    if not isinstance(text, str):
        raise ValueError("Gemini 응답에 parts[].text가 없습니다.")
    return text


def _build_http_poster(
    *, url: str, headers: Mapping[str, str], timeout_seconds: float
) -> JsonPoster:
    try:
        import httpx
    except ImportError as exc:  # pragma: no cover
        raise RuntimeError("httpx가 필요합니다. Conda 환경을 먼저 생성하세요.") from exc
    client = httpx.Client(
        headers={**headers, "Content-Type": "application/json"},
        timeout=timeout_seconds,
    )

    def post(payload: dict[str, Any]) -> dict[str, Any]:
        try:
            response = client.post(url, json=payload)
            response.raise_for_status()
            body = response.json()
        except httpx.HTTPError:
            raise RuntimeError(
                "GMS Chat 요청에 실패했습니다. HTTP 상태와 GMS 콘솔을 확인하세요."
            ) from None
        except ValueError:
            raise RuntimeError("GMS Chat 응답이 올바른 JSON이 아닙니다.") from None
        if not isinstance(body, dict):
            raise ValueError("GMS Chat 응답이 JSON 객체가 아닙니다.")
        return body

    return post
