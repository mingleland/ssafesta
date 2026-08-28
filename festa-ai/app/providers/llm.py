"""RAG 서비스가 Provider 원문 응답 없이 LLM token stream을 소비하게 하는 계약이다."""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Literal, Protocol, runtime_checkable


type LLMRole = Literal["system", "user", "assistant"]


@dataclass(frozen=True, slots=True)
class LLMMessage:
    """Provider에 전달할 역할 기반 메시지다."""

    role: LLMRole
    content: str


@dataclass(frozen=True, slots=True)
class LLMRequest:
    """Provider와 무관한 하나의 LLM 생성 요청이다."""

    messages: tuple[LLMMessage, ...]


@dataclass(frozen=True, slots=True)
class LLMToken:
    """Provider 원문 event에서 분리된 정규화 token delta다."""

    text: str


@runtime_checkable
class LLMProvider(Protocol):
    """LLM 응답을 정규화된 비동기 token stream으로 제공한다."""

    @property
    def model_id(self) -> str:
        """호출과 관측성에 사용할 안정적인 LLM 모델 식별자다."""
        ...

    def stream(self, request: LLMRequest) -> AsyncIterator[LLMToken]:
        """Provider 원문 event를 노출하지 않고 token delta만 반환한다."""
        ...
