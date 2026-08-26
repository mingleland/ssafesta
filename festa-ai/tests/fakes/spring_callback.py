from dataclasses import dataclass, field
from typing import Any


@dataclass
class CallbackRecord:
    status: str
    payload: dict[str, Any]


class FakeSpringStatusCallback:
    def __init__(self) -> None:
        self.records: list[CallbackRecord] = []
        self.fail_next = False

    async def send(self, status: str, payload: dict[str, Any]) -> None:
        if self.fail_next:
            self.fail_next = False
            raise RuntimeError("injected callback failure")
        self.records.append(CallbackRecord(status=status, payload=dict(payload)))
