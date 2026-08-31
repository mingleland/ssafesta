"""PDF 텍스트·페이지 추출 (T045)."""

from __future__ import annotations

import io

from pypdf import PdfReader
from pypdf.errors import PdfReadError

from .document_parser import DocumentParseError, ParsedPage, ScannedDocumentError


class PdfDocumentParser:
    def parse(self, content: bytes) -> list[ParsedPage]:
        try:
            reader = PdfReader(io.BytesIO(content))
            pages = [
                ParsedPage(page_number=index, text=(page.extract_text() or "").strip())
                for index, page in enumerate(reader.pages, start=1)
            ]
        except PdfReadError as exc:
            raise DocumentParseError("PDF를 읽을 수 없습니다.") from exc

        extracted = [page for page in pages if page.text]
        if not extracted:
            raise ScannedDocumentError("텍스트를 추출할 수 없는 스캔 PDF입니다.")
        return extracted
