"""PDF 텍스트·페이지 추출 테스트 (T045)."""

from __future__ import annotations

import io

import pytest
from fpdf import FPDF

from app.providers.document_parser import DocumentParseError, ScannedDocumentError
from app.providers.pdf_parser import PdfDocumentParser


def _build_pdf(page_texts: list[str]) -> bytes:
    pdf = FPDF()
    for text in page_texts:
        pdf.add_page()
        if text:
            pdf.set_font("Helvetica", size=12)
            pdf.cell(0, 10, text)
    return bytes(pdf.output())


def test_parse_extracts_text_per_page_in_order() -> None:
    content = _build_pdf(["first page text", "second page text"])

    pages = PdfDocumentParser().parse(content)

    assert [p.page_number for p in pages] == [1, 2]
    assert "first page text" in pages[0].text
    assert "second page text" in pages[1].text


def test_parse_skips_blank_pages_but_keeps_pages_with_text() -> None:
    content = _build_pdf(["", "has text"])

    pages = PdfDocumentParser().parse(content)

    assert len(pages) == 1
    assert pages[0].page_number == 2
    assert "has text" in pages[0].text


def test_parse_raises_scanned_document_error_when_no_page_has_text() -> None:
    content = _build_pdf(["", ""])

    with pytest.raises(ScannedDocumentError):
        PdfDocumentParser().parse(content)


def test_parse_raises_document_parse_error_for_invalid_pdf_bytes() -> None:
    with pytest.raises(DocumentParseError):
        PdfDocumentParser().parse(b"not a pdf")
