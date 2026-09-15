"""AI 구조화 로그의 민감정보 배제 계약을 검증한다."""

from __future__ import annotations

import logging

import pytest

from app.core.logging import StructuredLogFormatter, log_event


def test_structured_formatter_outputs_only_whitelisted_fields() -> None:
    record = logging.LogRecord(
        "test.logger", logging.INFO, __file__, 1, "conversation_stream_failed", (), None
    )
    record.conversation_id = "conv_1"
    record.error_code = "LLM_TIMEOUT"
    record.question = "개인 원문 질문입니다"
    record.authorization = "Bearer secret"

    rendered = StructuredLogFormatter().format(record)

    assert '"conversation_id": "conv_1"' in rendered
    assert '"error_code": "LLM_TIMEOUT"' in rendered
    assert "개인 원문 질문입니다" not in rendered
    assert "Bearer secret" not in rendered


def test_log_event_rejects_unapproved_fields() -> None:
    with pytest.raises(ValueError, match="unsupported log fields"):
        log_event(logging.getLogger(__name__), logging.INFO, "event", question="원문")
