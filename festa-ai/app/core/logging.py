"""AI 처리 로그에서 허용된 식별자만 구조화해 민감 원문 노출을 막는다."""

from __future__ import annotations

import json
import logging
from datetime import date, datetime
from typing import Any


_FIELD_NAMES = frozenset(
    {
        "request_id",
        "conversation_id",
        "job_id",
        "document_id",
        "booth_id",
        "agent_id",
        "worker_id",
        "attempt_no",
        "status",
        "error_code",
        "timeout_phase",
        "intent",
    }
)


class StructuredLogFormatter(logging.Formatter):
    """`log_event`가 제공한 화이트리스트 필드만 JSON으로 출력한다."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": self.formatTime(record, "%Y-%m-%dT%H:%M:%S"),
            "level": record.levelname,
            "logger": record.name,
            "event": getattr(record, "event", "unstructured_log"),
        }
        payload.update(
            {
                field: getattr(record, field)
                for field in _FIELD_NAMES
                if hasattr(record, field)
            }
        )
        return json.dumps(payload, ensure_ascii=False, default=_json_default)


def configure_logging(level: str) -> None:
    root = logging.getLogger()
    logging.basicConfig(level=level)
    root.setLevel(level)
    for handler in root.handlers:
        handler.setFormatter(StructuredLogFormatter())


def log_event(
    logger: logging.Logger,
    level: int,
    event: str,
    **fields: str | int | None,
) -> None:
    """원문 대신 검증된 식별자만 남기는 공통 AI 로그 진입점이다."""
    unknown = set(fields) - _FIELD_NAMES
    if unknown:
        raise ValueError(f"unsupported log fields: {sorted(unknown)}")
    logger.log(
        level,
        event,
        extra={
            "event": event,
            **{key: value for key, value in fields.items() if value is not None},
        },
    )


def _json_default(value: object) -> str:
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    return str(value)
