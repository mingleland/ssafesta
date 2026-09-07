"""FastAPI->Spring Chunk Search client (spec 008 FR-003/FR-005a, S15P21A604-449 이후 T049).

Search ownership moved to Spring's Business DB/pgvector (`S15P21A604-449`).
FastAPI no longer connects to any document DB — it only embeds the question
and calls `POST /internal/ai/chunk-search`
(`specs/008-ai-conversation-rag/contracts/spring-chunk-search-api.yaml`).

No retry here, unlike `SpringBoothAccessClient` — a failed search surfaces as
one `error` SSE event and the user retries the whole question with a new
`requestId`, so a client-side retry would just duplicate that decision.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import httpx


class SpringChunkSearchUnavailable(RuntimeError):
    """Spring did not return a parseable `200` result for this search."""


@dataclass(frozen=True, slots=True)
class ChunkScope:
    """Server-owned immutable Booth and Agent search boundary."""

    booth_id: int
    agent_id: int

    def __post_init__(self) -> None:
        if self.booth_id <= 0 or self.agent_id <= 0:
            raise ValueError("booth_id and agent_id must be positive")


@dataclass(frozen=True, slots=True)
class RetrievedChunk:
    """One `ChunkSearchItem` — Spring already applied Scope + READY filtering."""

    document_id: int
    chunk_id: int
    content: str
    page_number: int | None
    section: str | None
    original_filename: str
    distance: float


class SpringChunkSearchClient:
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
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_seconds, connect=timeout_seconds)
        )
        self._timeout_seconds = timeout_seconds

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def search(
        self,
        *,
        scope: ChunkScope,
        query_embedding: Sequence[float],
        top_k: int,
    ) -> tuple[RetrievedChunk, ...]:
        try:
            response = await self._client.post(
                f"{self._base_url}/internal/ai/chunk-search",
                json={
                    "boothId": scope.booth_id,
                    "agentId": scope.agent_id,
                    "queryEmbedding": list(query_embedding),
                    "topK": top_k,
                },
                headers={"Authorization": f"Bearer {self._service_token}"},
                timeout=httpx.Timeout(self._timeout_seconds, connect=self._timeout_seconds),
            )
        except httpx.HTTPError as exc:
            raise SpringChunkSearchUnavailable("Spring chunk-search request failed") from exc

        if response.status_code != 200:
            raise SpringChunkSearchUnavailable(
                f"Spring chunk-search returned status {response.status_code}"
            )
        return self._parse(response.json())

    @staticmethod
    def _parse(body: object) -> tuple[RetrievedChunk, ...]:
        if not isinstance(body, dict) or not isinstance(body.get("items"), list):
            raise SpringChunkSearchUnavailable("Spring chunk-search response is not an object")

        chunks: list[RetrievedChunk] = []
        for item in body["items"]:
            try:
                chunks.append(
                    RetrievedChunk(
                        document_id=item["documentId"],
                        chunk_id=item["chunkNo"],
                        content=item["content"],
                        page_number=item.get("pageNumber"),
                        section=item.get("section"),
                        original_filename=item["originalFilename"],
                        distance=float(item["distance"]),
                    )
                )
            except (KeyError, TypeError, ValueError) as exc:
                raise SpringChunkSearchUnavailable(
                    "Spring chunk-search item is malformed"
                ) from exc
        return tuple(chunks)
