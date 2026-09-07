"""FastAPI->Spring 문서 처리 결과 client (S15P21A604-124, GitLab #119 §3).

계약: `specs/007-ai-agent-document/contracts/document-result-api.yaml`.
FastAPI는 문서 Job을 소유하지 않으므로(S15P21A604-449) 이 client가 만든
chunk-batches/finalize/heartbeat/failed 호출이 처리 결과의 유일한 전달 경로다.

- `409`는 lease를 잃은 이전 attempt의 늦은 결과다 — 호출자는 처리를 멈춰야 한다.
- `410`은 Job이 끝났거나 삭제됐다는 뜻이다 — 마찬가지로 멈춘다.
- `400`은 요청 자체의 검증 실패다 (재시도해도 같은 결과).

일시적 네트워크 실패에 대한 재시도는 S15P21A604-125가 이 client를 감싸 추가한다.
"""

from __future__ import annotations

import asyncio
from collections.abc import Sequence
from dataclasses import dataclass

import httpx


class SpringDocumentResultUnavailable(RuntimeError):
    """일시적 네트워크 실패이거나 예상치 못한 응답이다."""


class SpringDocumentResultStaleAttempt(RuntimeError):
    """`409` — 현재 Job의 attempt가 이 호출의 attemptNo보다 앞서 있다."""


class SpringDocumentResultJobGone(RuntimeError):
    """`410` — Job이 끝났거나(SUCCEEDED/DEAD/CANCELLED) 삭제됐다."""


class SpringDocumentResultValidationFailed(RuntimeError):
    """`400` — 요청 형식·값 검증 실패다. 재시도해도 결과가 같다."""

    def __init__(self, body: object) -> None:
        super().__init__(f"document-result validation failed: {body!r}")
        self.body = body


@dataclass(frozen=True, slots=True)
class ChunkBatchItem:
    """`ChunkPayload` DTO — Spring이 저장할 chunk 하나다."""

    chunk_no: int
    content: str
    embedding: Sequence[float]
    embedding_model_id: str
    page_number: int | None
    section: str | None


class SpringDocumentResultClient:
    def __init__(
        self,
        *,
        base_url: str,
        service_token: str,
        timeout_seconds: float,
        client: httpx.AsyncClient | None = None,
        max_attempts: int = 3,
        retry_backoff_seconds: float = 0.5,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts는 1 이상이어야 합니다.")
        self._base_url = base_url.rstrip("/")
        self._service_token = service_token
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_seconds, connect=timeout_seconds)
        )
        self._timeout_seconds = timeout_seconds
        self._max_attempts = max_attempts
        self._retry_backoff_seconds = retry_backoff_seconds

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def chunk_batch(
        self,
        *,
        job_id: int,
        attempt_no: int,
        batch_seq: int,
        chunks: Sequence[ChunkBatchItem],
    ) -> None:
        await self._post(
            f"/document-jobs/{job_id}/chunk-batches",
            {
                "attemptNo": attempt_no,
                "batchSeq": batch_seq,
                "chunks": [
                    {
                        "chunkNo": chunk.chunk_no,
                        "content": chunk.content,
                        "embedding": list(chunk.embedding),
                        "embeddingModelId": chunk.embedding_model_id,
                        "pageNumber": chunk.page_number,
                        "section": chunk.section,
                    }
                    for chunk in chunks
                ],
            },
        )

    async def finalize(
        self,
        *,
        job_id: int,
        attempt_no: int,
        source_hash: str,
        total_chunk_count: int,
        embedding_model_id: str,
    ) -> None:
        await self._post(
            f"/document-jobs/{job_id}/finalize",
            {
                "attemptNo": attempt_no,
                "sourceHash": source_hash,
                "totalChunkCount": total_chunk_count,
                "embeddingModelId": embedding_model_id,
            },
        )

    async def heartbeat(self, *, job_id: int, attempt_no: int) -> None:
        await self._post(
            f"/document-jobs/{job_id}/heartbeat", {"attemptNo": attempt_no}
        )

    async def failed(
        self,
        *,
        job_id: int,
        attempt_no: int,
        failure_code: str,
        retryable: bool,
        message: str | None = None,
    ) -> None:
        body: dict[str, object] = {
            "attemptNo": attempt_no,
            "failureCode": failure_code,
            "retryable": retryable,
        }
        if message is not None:
            body["message"] = message
        await self._post(f"/document-jobs/{job_id}/failed", body)

    async def _post(self, path: str, json_body: dict[str, object]) -> None:
        """`400`/`409`/`410`은 재시도해도 결과가 같아 즉시 올린다.

        timeout·connect 실패와 `5xx`만 일시적 장애로 보고 `max_attempts`까지
        재시도한다 — Embedding까지 끝낸 결과를 네트워크 한 번 실패로 버리지
        않기 위해서다 (S15P21A604-125).
        """
        last_error: Exception | None = None
        for attempt in range(1, self._max_attempts + 1):
            try:
                response = await self._client.post(
                    f"{self._base_url}/internal/ai{path}",
                    json=json_body,
                    headers={"Authorization": f"Bearer {self._service_token}"},
                    timeout=httpx.Timeout(
                        self._timeout_seconds, connect=self._timeout_seconds
                    ),
                )
            except httpx.HTTPError as exc:
                last_error = exc
            else:
                if response.status_code == 204:
                    return
                if response.status_code == 400:
                    raise SpringDocumentResultValidationFailed(_safe_json(response))
                if response.status_code == 409:
                    raise SpringDocumentResultStaleAttempt(path)
                if response.status_code == 410:
                    raise SpringDocumentResultJobGone(path)
                if response.status_code < 500:
                    raise SpringDocumentResultUnavailable(
                        f"document-result returned unexpected status "
                        f"{response.status_code}: {path}"
                    )
                last_error = SpringDocumentResultUnavailable(
                    f"document-result returned status {response.status_code}: {path}"
                )

            if attempt < self._max_attempts:
                await asyncio.sleep(self._retry_backoff_seconds)

        raise SpringDocumentResultUnavailable(
            f"document-result request failed after {self._max_attempts} attempts: {path}"
        ) from last_error


def _safe_json(response: httpx.Response) -> object:
    try:
        return response.json()
    except ValueError:
        return response.text
