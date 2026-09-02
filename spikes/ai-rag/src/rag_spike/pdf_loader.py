"""PDF 또는 텍스트 문서를 청킹 입력으로 읽는다."""

from __future__ import annotations

from pathlib import Path

from .models import PageText


def load_pdf_pages(path: Path) -> list[PageText]:
    try:
        from pypdf import PdfReader
    except ImportError as exc:  # pragma: no cover - 설치 오류 경로
        raise RuntimeError("pypdf가 필요합니다. Conda 환경을 먼저 생성하세요.") from exc

    if not path.is_file():
        raise FileNotFoundError(f"PDF를 찾을 수 없습니다: {path}")
    reader = PdfReader(path)
    pages = [PageText(page=index, text=(page.extract_text() or "").strip()) for index, page in enumerate(reader.pages, 1)]
    extracted = [page for page in pages if page.text]
    if not extracted:
        raise ValueError("텍스트를 추출할 수 없습니다. 스캔 PDF는 이 스파이크 범위 밖입니다.")
    return extracted


def load_document_pages(path: Path) -> list[PageText]:
    if path.suffix.lower() == ".pdf":
        return load_pdf_pages(path)
    if path.suffix.lower() in {".md", ".txt"}:
        text = path.read_text(encoding="utf-8").strip()
        if not text:
            raise ValueError(f"텍스트 문서가 비어 있습니다: {path}")
        return [PageText(page=1, text=text)]
    raise ValueError("지원 문서 형식은 PDF, Markdown, TXT입니다.")
