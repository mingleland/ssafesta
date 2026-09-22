"""FastAPI->Spring 일일 미션 마커 통보 클라이언트 (spec 022 FR-003a, S15P21A604-955).

AI 대화는 nginx 가 `/ai/v1/**` 를 FastAPI 로 바로 보내므로 Spring 에 아무 흔적도 남지
않는다. 그래서 `AI_CONSULT` 미션은 이 통보가 유일한 사실 근거다.

Booth Access 와 달리 **Fail Closed 가 아니다.** 실패해도 대화는 성공해야 하고(FR-004a),
재시도도 하지 않는다 — 대화 생성 경로에 붙는 호출이라 지연을 늘리는 쪽이 손해가 크고,
같은 회원이 다시 말을 걸면 그때 다시 시도된다. 호출부가 좁은 except 하나로 끝낼 수 있도록
timeout·연결 오류·비2xx 응답을 전부 `SpringMissionMarkerUnavailable` 하나로 모은다.
"""

from __future__ import annotations

import httpx


class SpringMissionMarkerUnavailable(RuntimeError):
    """Spring 이 마커를 받지 못했다 — 호출부는 이것만 잡고 대화를 계속한다."""


class SpringMissionMarkerClient:
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

    async def mark_ai_consult(self, *, user_id: int) -> None:
        """멱등하다 — 같은 KST 일자의 재통보는 Spring 이 그대로 204 로 받는다."""
        try:
            response = await self._client.post(
                f"{self._base_url}/internal/ai/mission/ai-consult",
                json={"userId": user_id},
                headers={"Authorization": f"Bearer {self._service_token}"},
                timeout=httpx.Timeout(self._timeout_seconds, connect=self._timeout_seconds),
            )
        except httpx.HTTPError as exc:
            raise SpringMissionMarkerUnavailable("Spring mission marker call failed") from exc
        # 계약상 성공은 204 하나뿐이다. `>= 400` 으로 거르면 리다이렉트(httpx 는 기본적으로
        # 따라가지 않는다)와 204 아닌 2xx 가 마커를 쓰지 않은 채 성공으로 읽힌다.
        if response.status_code != 204:
            raise SpringMissionMarkerUnavailable(
                f"Spring mission marker answered {response.status_code}, expected 204"
            )
