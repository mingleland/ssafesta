from __future__ import annotations

from collections.abc import AsyncIterator, Sequence

from app.providers.llm import LLMRequest, LLMToken


class FakeLLMProvider:
    """Replays a fixed token sequence, or raises a fixed exception mid-stream."""

    model_id = "fake-llm-v1"

    def __init__(
        self,
        tokens: Sequence[str] = (),
        *,
        raise_after: Exception | None = None,
    ) -> None:
        self._tokens = tuple(tokens)
        self._raise_after = raise_after
        self.calls: list[LLMRequest] = []

    async def stream(self, request: LLMRequest) -> AsyncIterator[LLMToken]:
        self.calls.append(request)
        for text in self._tokens:
            yield LLMToken(text=text)
        if self._raise_after is not None:
            raise self._raise_after
