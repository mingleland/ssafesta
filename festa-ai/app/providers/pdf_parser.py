"""PDF 텍스트·페이지 추출 (T045).

pypdf의 기본 텍스트 추출은 글자를 space 없이 좌표로만 배치하는 PDF(일부 내보내기
도구의 출력)에서 단어를 이어붙인다. pdfplumber는 글자 단위 좌표 간격으로 공백을
판단해 이 경우에도 안정적이므로 사용한다.
"""

from __future__ import annotations

import io
import logging

import pdfplumber
from pdfplumber.utils.exceptions import PdfminerException

from .document_parser import DocumentParseError, ParsedPage, ScannedDocumentError

# pdfminer는 FontBBox 누락 등 손상되지 않은 subset 폰트에도 문서당 수십~수백 줄의
# WARNING을 남긴다. 파싱 실패 여부와 무관한 잡음이므로 ERROR 미만은 조용히 시킨다.
logging.getLogger("pdfminer").setLevel(logging.ERROR)


class PdfDocumentParser:
    def parse(self, content: bytes) -> list[ParsedPage]:
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
