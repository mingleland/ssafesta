from __future__ import annotations

from app.clients.spring_booth_access import BoothAccessResult, SpringBoothAccessUnavailable


class FakeSpringBoothAccessClient:
    def __init__(self) -> None:
        self.result: BoothAccessResult | None = None
        self.raise_unavailable = False
        self.calls: list[tuple[int, int]] = []

    async def check(self, *, booth_id: int, agent_id: int) -> BoothAccessResult:
        self.calls.append((booth_id, agent_id))
        if self.raise_unavailable:
            raise SpringBoothAccessUnavailable("injected failure")
        assert self.result is not None, "set .result before calling check()"
        return self.result
