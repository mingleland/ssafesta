"""PDF 텍스트·페이지 추출 테스트 (T045)."""

from __future__ import annotations

import io
import logging

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


def _build_pdf_with_word_runs_positioned_apart(words: list[str], gap: float) -> bytes:
    """단어마다 별도 텍스트 조각으로 위치만 옮겨 그리고 space 글자는 쓰지 않는다.

    실제 업로드된 PDF(폰트 subset 내보내기 도구)에서 단어 사이에 space 글자 없이
    좌표 간격만으로 띄어쓰기를 표현하는 사례를 재현한다. pypdf 기본 추출은 이 구조에서
    간격을 감지하지 못하고 단어를 붙여버린다.
    """

    lines: list[str] = []
    x = 10.0
    for word in words:
        lines += ["BT", "/F1 12 Tf", f"{x:.2f} 50 Td", f"({word}) Tj", "ET"]
        x += gap
    content_stream = ("\n".join(lines) + "\n").encode()

    objects = [
        b"1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n",
        b"2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n",
        b"3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 100] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>endobj\n",
        b"4 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\n",
        b"5 0 obj<< /Length " + str(len(content_stream)).encode() + b" >>stream\n"
        + content_stream
        + b"endstream\nendobj\n",
    ]

    pdf_bytes = b"%PDF-1.4\n"
    offsets = [0]
    for obj in objects:
        offsets.append(len(pdf_bytes))
        pdf_bytes += obj
    xref_start = len(pdf_bytes)
    pdf_bytes += b"xref\n0 " + str(len(objects) + 1).encode() + b"\n0000000000 65535 f \n"
    for offset in offsets[1:]:
        pdf_bytes += ("%010d 00000 n \n" % offset).encode()
    pdf_bytes += (
        b"trailer<< /Size "
        + str(len(objects) + 1).encode()
        + b" /Root 1 0 R >>\nstartxref\n"
        + str(xref_start).encode()
        + b"\n%%EOF"
    )
    return pdf_bytes


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


def test_pdfminer_warning_noise_is_suppressed_at_module_import() -> None:
    """FontBBox 등 pdfminer 내부 WARNING은 문서마다 수십~수백 줄씩 찍혀 실제 서비스
    로그를 도배한다 (실제 PDF로 확인). ERROR 미만은 조용히 시킨다."""

    assert logging.getLogger("pdfminer").level >= logging.ERROR


def test_parse_keeps_words_separated_when_pdf_encodes_spacing_via_position_only() -> None:
    content = _build_pdf_with_word_runs_positioned_apart(["Basic", "Documentation"], gap=35)

    pages = PdfDocumentParser().parse(content)

    assert pages[0].text == "Basic Documentation"
