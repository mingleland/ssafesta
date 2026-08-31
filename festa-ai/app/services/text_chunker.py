"""페이지 메타데이터를 보존하며 토큰 단위 청크를 만든다 (T048).

chunk_size·overlap은 `S15P21A604-92` 스파이크 실측값이 research.md에 반영되기
전까지 코드에 기본값을 고정하지 않는다. 호출자가 설정(`Settings.chunk_size`,
`Settings.chunk_overlap`)에서 읽어 명시적으로 전달한다.
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
        tokens = list(codec.encode(page.text.strip()))
        if not tokens:
            continue
        for start in range(0, len(tokens), step):
            window = tokens[start : start + chunk_size]
            if not window:
                break
            content = codec.decode(window).strip()
            if content:
                chunks.append(
                    TextChunk(
                        chunk_no=chunk_no,
                        page_number=page.page_number,
                        section=None,
                        content=content,
                        token_count=len(window),
                    )
                )
                chunk_no += 1
            if start + chunk_size >= len(tokens):
                break

    if not chunks:
        raise ValueError("문서에서 임베딩할 텍스트 청크를 만들지 못했습니다.")
    return chunks
