"""페이지 메타데이터를 보존하는 토큰 단위 청킹 테스트 (T048)."""

from __future__ import annotations

import pytest
import tiktoken

from app.providers.document_parser import ParsedPage
from app.services.text_chunker import TikTokenCodec, chunk_pages


class _WordCodec:
    """공백 기준 1단어 = 1토큰인 예측 가능한 가짜 codec."""

    def encode(self, text: str) -> list[int]:
        return list(range(len(text.split())))

    def decode(self, tokens: list[int]) -> str:
        return " ".join(f"tok{token}" for token in tokens)


def test_chunk_pages_splits_single_page_into_overlapping_windows() -> None:
    pages = [ParsedPage(page_number=1, text="a b c d e f g h")]

    chunks = chunk_pages(pages, chunk_size=4, overlap=1, codec=_WordCodec())

    assert [c.token_count for c in chunks] == [4, 4, 2]
    assert all(c.page_number == 1 for c in chunks)
    assert [c.chunk_no for c in chunks] == [0, 1, 2]


def test_chunk_pages_ids_are_unique_and_sequential_across_pages() -> None:
    pages = [
        ParsedPage(page_number=1, text="a b c"),
        ParsedPage(page_number=2, text="d e f"),
    ]

    chunks = chunk_pages(pages, chunk_size=3, overlap=0, codec=_WordCodec())

    assert [c.page_number for c in chunks] == [1, 2]
    assert [c.chunk_no for c in chunks] == [0, 1]


def test_chunk_pages_skips_pages_with_no_tokens() -> None:
    pages = [
        ParsedPage(page_number=1, text=""),
        ParsedPage(page_number=2, text="only content"),
    ]

    chunks = chunk_pages(pages, chunk_size=10, overlap=0, codec=_WordCodec())

    assert len(chunks) == 1
    assert chunks[0].page_number == 2


def test_chunk_pages_rejects_non_positive_chunk_size() -> None:
    pages = [ParsedPage(page_number=1, text="a b c")]

    with pytest.raises(ValueError):
        chunk_pages(pages, chunk_size=0, overlap=0, codec=_WordCodec())


def test_chunk_pages_rejects_overlap_not_smaller_than_chunk_size() -> None:
    pages = [ParsedPage(page_number=1, text="a b c")]

    with pytest.raises(ValueError):
        chunk_pages(pages, chunk_size=4, overlap=4, codec=_WordCodec())


def test_chunk_pages_raises_when_no_page_yields_a_chunk() -> None:
    pages = [ParsedPage(page_number=1, text="")]

    with pytest.raises(ValueError):
        chunk_pages(pages, chunk_size=10, overlap=0, codec=_WordCodec())


def test_tiktoken_codec_round_trips_real_text() -> None:
    codec = TikTokenCodec()
    pages = [ParsedPage(page_number=1, text="SSAFY FESTA 부스 운영 시간은 오전 10시부터입니다.")]

    chunks = chunk_pages(pages, chunk_size=8, overlap=2, codec=codec)

    encoding = tiktoken.get_encoding("cl100k_base")
    assert chunks[0].token_count == len(encoding.encode(chunks[0].content))
    assert "부스" in "".join(c.content for c in chunks)
