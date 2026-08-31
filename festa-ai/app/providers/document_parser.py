"""문서 파서 계약과 구현 (T018, T046).

Spring이 검증한 `content_type`(PDF/Markdown/TXT)으로 추출 방식을 분기한다.

pdfplumber는 글자 단위 좌표 간격으로 공백을 판단해, space 글자 없이 좌표로만
단어를 배치하는 PDF(일부 내보내기 도구의 출력)에서도 안정적이다. pypdf 기본
추출은 이런 PDF에서 단어를 붙여버린다.
"""

from __future__ import annotations

import io
import logging
from dataclasses import dataclass
from typing import Protocol, runtime_checkable

import pdfplumber
from pdfplumber.utils.exceptions import PdfminerException

from app.api.schemas.documents import DocumentContentType

# pdfminer는 FontBBox 누락 등 손상되지 않은 subset 폰트에도 문서당 수십~수백 줄의
# WARNING을 남긴다. 파싱 실패 여부와 무관한 잡음이므로 ERROR 미만은 조용히 시킨다.
logging.getLogger("pdfminer").setLevel(logging.ERROR)


@dataclass(frozen=True, slots=True)
class ParsedPage:
    """문서 한 페이지(MD·TXT는 문서 전체)에서 추출된 텍스트."""

    page_number: int
    text: str


class DocumentParseError(Exception):
    """문서를 열거나 읽을 수 없을 때 발생한다 (PARSE_FAILED)."""


class ScannedDocumentError(DocumentParseError):
    """텍스트를 추출할 수 없는 스캔 PDF일 때 발생한다 (UNSUPPORTED_SCAN_PDF)."""


@runtime_checkable
class DocumentParser(Protocol):
    """`content_type`에 맞는 방식으로 페이지 단위 텍스트를 반환한다."""

    def parse(self, content: bytes, content_type: DocumentContentType) -> list[ParsedPage]: ...


def _parse_pdf(content: bytes) -> list[ParsedPage]:
    try:
        with pdfplumber.open(io.BytesIO(content)) as document:
            pages = [
                ParsedPage(page_number=index, text=(page.extract_text() or "").strip())
                for index, page in enumerate(document.pages, start=1)
            ]
    except PdfminerException as exc:
        raise DocumentParseError("PDF를 읽을 수 없습니다.") from exc

    extracted = [page for page in pages if page.text]
    if not extracted:
        raise ScannedDocumentError("텍스트를 추출할 수 없는 스캔 PDF입니다.")
    return extracted


def _parse_plain_text(content: bytes) -> list[ParsedPage]:
    try:
        text = content.decode("utf-8").strip()
    except UnicodeDecodeError as exc:
        raise DocumentParseError("UTF-8로 디코딩할 수 없습니다.") from exc

    if not text:
        raise DocumentParseError("빈 문서입니다.")
    return [ParsedPage(page_number=1, text=text)]


class DefaultDocumentParser:
    """`content_type`에 따라 PDF·Markdown·TXT 추출을 분기하는 기본 구현."""

    def parse(self, content: bytes, content_type: DocumentContentType) -> list[ParsedPage]:
        if content_type == DocumentContentType.PDF:
            return _parse_pdf(content)
        return _parse_plain_text(content)
