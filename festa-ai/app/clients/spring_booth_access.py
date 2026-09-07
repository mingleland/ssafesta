"""FastAPI->Spring Booth Access validation client (spec 008 FR-024/FR-025).

Calls `GET /internal/ai/booth-access` once with a 1-second timeout and one
retry on failure — two calls, ~2 seconds worst case (research.md section 3).
Any failure to get a parseable response after the retry is Fail Closed: the
caller must refuse to create the Conversation, never default to allowed.
"""

from __future__ import annotations

from dataclasses import dataclass

import httpx


class SpringBoothAccessUnavailable(RuntimeError):
    """Spring did not answer with a parseable result within the retry budget."""


@dataclass(frozen=True, slots=True)
class BoothAccessResult:
    allowed: bool
    lease_ends_at: str | None
    denial_code: str | None


class SpringBoothAccessClient:
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

    async def check(self, *, booth_id: int, agent_id: int) -> BoothAccessResult:
        last_error: Exception | None = None
        for _attempt in range(2):
            try:
                response = await self._client.get(
                    f"{self._base_url}/internal/ai/booth-access",
                    params={"boothId": booth_id, "agentId": agent_id},
                    headers={"Authorization": f"Bearer {self._service_token}"},
                    timeout=httpx.Timeout(self._timeout_seconds, connect=self._timeout_seconds),
                )
                response.raise_for_status()
                return self._parse(response.json())
            except (httpx.TimeoutException, httpx.HTTPError) as exc:
                last_error = exc
                continue
        raise SpringBoothAccessUnavailable(
            "Spring booth-access validation failed after retry"
        ) from last_error

    @staticmethod
    def _parse(body: object) -> BoothAccessResult:
        if not isinstance(body, dict):
            raise SpringBoothAccessUnavailable("Spring booth-access response is not an object")
        allowed = body.get("allowed")
        if not isinstance(allowed, bool):
            raise SpringBoothAccessUnavailable("Spring booth-access response missing `allowed`")
        lease_ends_at = body.get("leaseEndsAt")
        if lease_ends_at is not None and not isinstance(lease_ends_at, str):
            raise SpringBoothAccessUnavailable("Spring booth-access `leaseEndsAt` is malformed")
        denial_code = body.get("denialCode")
        if denial_code is not None and not isinstance(denial_code, str):
            raise SpringBoothAccessUnavailable("Spring booth-access `denialCode` is malformed")
        if allowed and denial_code is not None:
            raise SpringBoothAccessUnavailable("Spring booth-access allowed=true carries a denialCode")
        if not allowed and denial_code is None:
            raise SpringBoothAccessUnavailable("Spring booth-access allowed=false is missing denialCode")
        return BoothAccessResult(
            allowed=allowed, lease_ends_at=lease_ends_at, denial_code=denial_code
        )
