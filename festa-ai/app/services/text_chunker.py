"""페이지 메타데이터를 보존하며 spike 확정값으로 토큰 단위 청크를 만든다 (T048).

호출자는 `Settings`의 기본값 900 tokens·overlap 180 tokens를 명시적으로 전달한다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Sequence

from app.providers.document_parser import ParsedPage


class TokenCodec(Protocol):
    def encode(self, text: str) -> Sequence[int]: ...

    def decode(self, tokens: Sequence[int]) -> str: ...


class TikTokenCodec:
    def __init__(self, encoding_name: str = "cl100k_base") -> None:
        import tiktoken

        self._encoding = tiktoken.get_encoding(encoding_name)

    def encode(self, text: str) -> Sequence[int]:
        return self._encoding.encode(text)

    def decode(self, tokens: Sequence[int]) -> str:
        return self._encoding.decode(list(tokens))

    def split_windows(
        self, text: str, *, chunk_size: int, overlap: int
    ) -> list[tuple[str, int]]:
        """멀티바이트 문자의 토큰 중간을 잘라도 원문에 대체 문자를 만들지 않는다."""
        tokens = self._encoding.encode(text)
        decoded, offsets = self._encoding.decode_with_offsets(tokens)
        if decoded != text:
            raise ValueError("토큰 offset을 원문 문자 위치로 변환하지 못했습니다.")

        step = chunk_size - overlap
        windows: list[tuple[str, int]] = []
        for start in range(0, len(tokens), step):
            end = min(start + chunk_size, len(tokens))
            char_start = offsets[start]
            char_end = offsets[end] if end < len(offsets) else len(text)
            content = text[char_start:char_end].strip()
            if content:
                windows.append((content, end - start))
            if end >= len(tokens):
                break
        return windows


@dataclass(frozen=True, slots=True)
class TextChunk:
    chunk_no: int
    page_number: int
    section: str | None
    content: str
    token_count: int


def chunk_pages(
    pages: Sequence[ParsedPage],
    *,
    chunk_size: int,
    overlap: int,
    codec: TokenCodec,
) -> list[TextChunk]:
    if chunk_size <= 0:
        raise ValueError("chunk_size는 1 이상이어야 합니다.")
    if overlap < 0 or overlap >= chunk_size:
        raise ValueError("overlap은 0 이상 chunk_size 미만이어야 합니다.")

    step = chunk_size - overlap
    chunks: list[TextChunk] = []
    chunk_no = 0
    for page in pages:
        page_text = page.text.strip()
        tokens = list(codec.encode(page_text))
        if not tokens:
            continue
        splitter = getattr(codec, "split_windows", None)
        if splitter is not None:
            windows = splitter(page_text, chunk_size=chunk_size, overlap=overlap)
        else:
            windows = []
            for start in range(0, len(tokens), step):
                window = tokens[start : start + chunk_size]
                if not window:
                    break
                windows.append((codec.decode(window).strip(), len(window)))
                if start + chunk_size >= len(tokens):
                    break

        for content, token_count in windows:
            if content:
                chunks.append(
                    TextChunk(
                        chunk_no=chunk_no,
                        page_number=page.page_number,
                        section=None,
                        content=content,
                        token_count=token_count,
                    )
                )
                chunk_no += 1

    if not chunks:
        raise ValueError("문서에서 임베딩할 텍스트 청크를 만들지 못했습니다.")
    return chunks
