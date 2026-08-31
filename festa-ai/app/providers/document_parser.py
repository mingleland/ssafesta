"""문서 파서 공통 계약: 입력 형식과 무관하게 페이지 단위 텍스트를 반환한다."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class ParsedPage:
    page_number: int
    text: str


class DocumentParseError(Exception):
    """문서를 열거나 읽을 수 없을 때 발생한다 (PARSE_FAILED)."""


class ScannedDocumentError(DocumentParseError):
    """텍스트를 추출할 수 없는 스캔 문서일 때 발생한다 (UNSUPPORTED_SCAN_PDF)."""


class DocumentParser(Protocol):
    def parse(self, content: bytes) -> list[ParsedPage]: ...
