from __future__ import annotations

from app.clients.spring_mission import SpringMissionMarkerUnavailable


class FakeSpringMissionMarkerClient:
    def __init__(self) -> None:
        self.raise_unavailable = False
        self.calls: list[int] = []

    async def mark_ai_consult(self, *, user_id: int) -> None:
        self.calls.append(user_id)
        if self.raise_unavailable:
            raise SpringMissionMarkerUnavailable("injected failure")
