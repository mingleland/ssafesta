"""검색 후보를 재정렬한다: 유사도 절단, Cross-Encoder, GMS LLM 세 방식.

각 방식의 실패는 조용히 삼키지 않는다 — ``RerankResult.success``와 ``error``에
드러내고, 후보는 bi-encoder 원 순서로 되돌려 나머지 질문 평가가 계속되게 한다.
"""

from __future__ import annotations

import json
import re
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol

from .models import ModelSpec, SearchHit


DEFAULT_CROSS_ENCODER_MODEL = "cross-encoder/ms-marco-MiniLM-L-6-v2"
DEFAULT_OPENAI_CHAT_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
DEFAULT_GEMINI_GENERATE_URL_TEMPLATE = (
    "https://gms.ssafy.io/gmsapi/generativelanguage.googleapis.com/"
    "v1beta/models/{model}:generateContent"
)


class RerankMethod(str, Enum):
    NONE = "none"
    SIMILARITY_CUTOFF = "similarity_cutoff"
    CROSS_ENCODER = "cross_encoder"
    GMS_LLM = "gms_llm"


@dataclass(frozen=True)
class RerankResult:
    hits: tuple[SearchHit, ...]
    method: RerankMethod
    elapsed_seconds: float
    request_count: int
    estimated_credits: float
    success: bool
    error: str | None = None


def truncate_top_n(hits: Sequence[SearchHit], top_n: int) -> list[SearchHit]:
    if top_n <= 0:
        raise ValueError("top_n은 1 이상이어야 합니다.")
    return list(hits[:top_n])


def cutoff_by_similarity(hits: Sequence[SearchHit], *, max_distance: float) -> list[SearchHit]:
    if max_distance < 0:
        raise ValueError("max_distance는 0 이상이어야 합니다.")
    return [hit for hit in hits if hit.distance <= max_distance]


class CrossEncoderScorer(Protocol):
    def __call__(self, pairs: Sequence[tuple[str, str]]) -> Sequence[float]: ...


class CrossEncoderReranker:
    def __init__(
        self,
        *,
        model_name: str = DEFAULT_CROSS_ENCODER_MODEL,
        scorer: CrossEncoderScorer | None = None,
    ) -> None:
        self._model_name = model_name
        self._scorer = scorer or self._build_model_scorer(model_name)

    @staticmethod
    def _build_model_scorer(model_name: str) -> CrossEncoderScorer:
        try:
            from sentence_transformers import CrossEncoder
        except ImportError as exc:  # pragma: no cover
            raise RuntimeError(
                "sentence-transformers가 필요합니다. Conda 환경을 먼저 생성하세요."
            ) from exc
        model = CrossEncoder(model_name)

        def score(pairs: Sequence[tuple[str, str]]) -> Sequence[float]:
            return [float(value) for value in model.predict(list(pairs))]

        return score

    def rerank(self, *, query: str, hits: Sequence[SearchHit], top_n: int) -> RerankResult:
        if not hits:
            return RerankResult(
                hits=(),
                method=RerankMethod.CROSS_ENCODER,
                elapsed_seconds=0.0,
                request_count=0,
                estimated_credits=0.0,
                success=True,
            )
        started = time.perf_counter()
        try:
            scores = list(self._scorer([(query, hit.content) for hit in hits]))
            if len(scores) != len(hits):
                raise ValueError("Cross-Encoder 점수 개수가 후보 개수와 다릅니다.")
        except Exception as exc:  # noqa: BLE001 - 외부 모델 실패를 전부 표면화한다
            return RerankResult(
                hits=tuple(truncate_top_n(hits, min(top_n, len(hits)))),
                method=RerankMethod.CROSS_ENCODER,
                elapsed_seconds=time.perf_counter() - started,
                request_count=1,
                estimated_credits=0.0,
                success=False,
                error=f"Cross-Encoder 재정렬 실패: {exc}",
            )
        ordered = [
            item
            for _, item in sorted(
                zip(scores, hits), key=lambda pair: pair[0], reverse=True
            )
        ]
        return RerankResult(
            hits=tuple(truncate_top_n(ordered, min(top_n, len(ordered)))),
            method=RerankMethod.CROSS_ENCODER,
            elapsed_seconds=time.perf_counter() - started,
            request_count=1,
            estimated_credits=0.0,
            success=True,
        )


class JsonPoster(Protocol):
    def __call__(self, payload: dict[str, Any]) -> dict[str, Any]: ...


class PosterFactory(Protocol):
    def __call__(self, model: ModelSpec) -> JsonPoster: ...


class GmsLlmReranker:
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

    def rerank(
        self, *, query: str, hits: Sequence[SearchHit], top_n: int, model: ModelSpec
    ) -> RerankResult:
        if not hits:
            return RerankResult(
                hits=(),
                method=RerankMethod.GMS_LLM,
                elapsed_seconds=0.0,
                request_count=0,
                estimated_credits=0.0,
                success=True,
            )
        started = time.perf_counter()
        try:
            poster = self._poster_for(model)
            prompt = _build_listwise_prompt(query=query, hits=hits)
            if model.provider == "openai":
                body = poster(
                    {"model": model.model_id, "messages": [{"role": "user", "content": prompt}]}
                )
                text = _extract_openai_text(body)
            elif model.provider == "gemini":
                body = poster({"contents": [{"parts": [{"text": prompt}]}]})
                text = _extract_gemini_text(body)
            else:
                raise ValueError(f"지원하지 않는 LLM provider: {model.provider}")
            order = _parse_chunk_id_order(text, valid_ids={hit.chunk_id for hit in hits})
        except Exception as exc:  # noqa: BLE001 - 외부 LLM 실패를 전부 표면화한다
            return RerankResult(
                hits=tuple(truncate_top_n(hits, min(top_n, len(hits)))),
                method=RerankMethod.GMS_LLM,
                elapsed_seconds=time.perf_counter() - started,
                request_count=1,
                estimated_credits=model.credit_per_request,
                success=False,
                error=f"GMS LLM 재정렬 실패({model.model_id}): {exc}",
            )
        by_id = {hit.chunk_id: hit for hit in hits}
        ordered = [by_id[chunk_id] for chunk_id in order]
        ordered.extend(hit for hit in hits if hit.chunk_id not in order)
        return RerankResult(
            hits=tuple(truncate_top_n(ordered, min(top_n, len(ordered)))),
            method=RerankMethod.GMS_LLM,
            elapsed_seconds=time.perf_counter() - started,
            request_count=1,
            estimated_credits=model.credit_per_request,
            success=True,
        )


def _build_listwise_prompt(*, query: str, hits: Sequence[SearchHit]) -> str:
    candidates = "\n".join(
        f'- chunk_id="{hit.chunk_id}": {hit.content}' for hit in hits
    )
    return (
        "다음은 사용자 질문과 검색된 문서 청크 후보 목록이다. "
        "질문과 관련도가 높은 순서대로 chunk_id를 정렬해 "
        'JSON 배열 하나만 출력하라. 예: ["id1","id2"]. '
        "다른 설명이나 텍스트는 출력하지 마라.\n\n"
        f"질문: {query}\n\n후보:\n{candidates}"
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


def _parse_chunk_id_order(text: str, *, valid_ids: set[str]) -> list[str]:
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if match is None:
        raise ValueError(f"응답에서 JSON 배열을 찾을 수 없습니다: {text!r}")
    try:
        parsed = json.loads(match.group(0))
    except json.JSONDecodeError as exc:
        raise ValueError(f"응답 JSON 파싱에 실패했습니다: {text!r}") from exc
    if not isinstance(parsed, list) or not all(isinstance(item, str) for item in parsed):
        raise ValueError(f"응답이 문자열 배열이 아닙니다: {parsed!r}")
    unknown = [chunk_id for chunk_id in parsed if chunk_id not in valid_ids]
    if unknown:
        raise ValueError(f"응답에 알 수 없는 chunk_id가 있습니다: {unknown}")
    return parsed


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
                "GMS LLM 요청에 실패했습니다. HTTP 상태와 GMS 콘솔을 확인하세요."
            ) from None
        except ValueError:
            raise RuntimeError("GMS LLM 응답이 올바른 JSON이 아닙니다.") from None
        if not isinstance(body, dict):
            raise ValueError("GMS LLM 응답이 JSON 객체가 아닙니다.")
        return body

    return post
