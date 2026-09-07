"""PDF 페이지 메타데이터를 보존하며 토큰 단위 청크를 만든다."""

from __future__ import annotations

import math
import re
from typing import Callable, Protocol, Sequence

from .models import Chunk, PageText


class TokenCodec(Protocol):
    def encode(self, text: str) -> Sequence[int]: ...

    def decode(self, tokens: Sequence[int]) -> str: ...


class TikTokenCodec:
    def __init__(self, encoding_name: str = "cl100k_base") -> None:
        try:
            import tiktoken
        except ImportError as exc:  # pragma: no cover - 설치 오류 경로
            raise RuntimeError("tiktoken이 필요합니다. Conda 환경을 먼저 생성하세요.") from exc
        self._encoding = tiktoken.get_encoding(encoding_name)

    def encode(self, text: str) -> Sequence[int]:
        return self._encoding.encode(text)

    def decode(self, tokens: Sequence[int]) -> str:
        return self._encoding.decode(list(tokens))

    def split_windows(
        self, text: str, *, chunk_size: int, overlap: int
    ) -> list[tuple[str, int]]:
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


def chunk_pages(
    pages: Sequence[PageText],
    *,
    chunk_size: int,
    overlap: int,
    codec: TokenCodec,
) -> list[Chunk]:
    if chunk_size <= 0:
        raise ValueError("chunk_size는 1 이상이어야 합니다.")
    if overlap < 0 or overlap >= chunk_size:
        raise ValueError("overlap은 0 이상 chunk_size 미만이어야 합니다.")

    step = chunk_size - overlap
    chunks: list[Chunk] = []
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
                    Chunk(
                        chunk_id=f"p{page.page:04d}-c{chunk_no:05d}",
                        page=page.page,
                        chunk_no=chunk_no,
                        token_count=token_count,
                        content=content,
                    )
                )
                chunk_no += 1
    if not chunks:
        raise ValueError("PDF에서 임베딩할 텍스트 청크를 만들지 못했습니다.")
    return chunks


_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n+")
_SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+")


def split_paragraphs(text: str) -> list[str]:
    return [block.strip() for block in _PARAGRAPH_SPLIT.split(text) if block.strip()]


def split_sentences(text: str) -> list[str]:
    normalized = " ".join(text.split())
    if not normalized:
        return []
    return [sentence.strip() for sentence in _SENTENCE_SPLIT.split(normalized) if sentence.strip()]


def chunk_pages_structural(
    pages: Sequence[PageText],
    *,
    chunk_size: int,
    codec: TokenCodec,
) -> list[Chunk]:
    """빈 줄로 구분되는 문단(블록) 경계에서만 자르는 구조적 청킹이다.

    블록 하나가 chunk_size를 넘으면 그 블록만 토큰 윈도우로 강제 분할한다.
    """

    if chunk_size <= 0:
        raise ValueError("chunk_size는 1 이상이어야 합니다.")
    chunks: list[Chunk] = []
    chunk_no = 0
    for page in pages:
        blocks = split_paragraphs(page.text)
        if not blocks:
            continue
        pending: list[str] = []
        pending_tokens = 0
        for block in blocks:
            block_tokens = len(list(codec.encode(block)))
            if block_tokens > chunk_size:
                if pending:
                    chunks.append(_join_blocks(page.page, chunk_no, pending, codec))
                    chunk_no += 1
                    pending, pending_tokens = [], 0
                for content, token_count in _split_oversized(block, chunk_size, codec):
                    chunks.append(
                        Chunk(
                            chunk_id=f"p{page.page:04d}-c{chunk_no:05d}",
                            page=page.page,
                            chunk_no=chunk_no,
                            token_count=token_count,
                            content=content,
                        )
                    )
                    chunk_no += 1
                continue
            if pending and pending_tokens + block_tokens > chunk_size:
                chunks.append(_join_blocks(page.page, chunk_no, pending, codec))
                chunk_no += 1
                pending, pending_tokens = [], 0
            pending.append(block)
            pending_tokens += block_tokens
        if pending:
            chunks.append(_join_blocks(page.page, chunk_no, pending, codec))
            chunk_no += 1
    if not chunks:
        raise ValueError("PDF에서 구조적 청크를 만들지 못했습니다.")
    return chunks


def _join_blocks(page: int, chunk_no: int, blocks: Sequence[str], codec: TokenCodec) -> Chunk:
    content = "\n\n".join(blocks)
    token_count = len(list(codec.encode(content)))
    return Chunk(
        chunk_id=f"p{page:04d}-c{chunk_no:05d}",
        page=page,
        chunk_no=chunk_no,
        token_count=token_count,
        content=content,
    )


def _split_oversized(text: str, chunk_size: int, codec: TokenCodec) -> list[tuple[str, int]]:
    splitter = getattr(codec, "split_windows", None)
    if splitter is not None:
        return splitter(text, chunk_size=chunk_size, overlap=0)
    tokens = list(codec.encode(text))
    windows: list[tuple[str, int]] = []
    for start in range(0, len(tokens), chunk_size):
        window = tokens[start : start + chunk_size]
        if not window:
            break
        windows.append((codec.decode(window).strip(), len(window)))
    return windows


def chunk_pages_semantic(
    pages: Sequence[PageText],
    *,
    chunk_size: int,
    codec: TokenCodec,
    sentence_embedder: Callable[[Sequence[str]], Sequence[Sequence[float]]],
    breakpoint_threshold: float = 0.3,
) -> list[Chunk]:
    """인접 문장 임베딩의 코사인 거리가 임계값을 넘는 지점을 경계로 삼는다.

    문장 임베딩은 호출자가 주입한다(HTTP 의존성을 이 모듈 밖에 둔다).
    같은 페이지 안에서만 거리를 비교하며, 페이지 경계는 항상 청크 경계다.
    """

    if chunk_size <= 0:
        raise ValueError("chunk_size는 1 이상이어야 합니다.")
    if not 0.0 < breakpoint_threshold < 2.0:
        raise ValueError("breakpoint_threshold는 0 초과 2 미만이어야 합니다.")

    page_sentences: list[tuple[int, str]] = [
        (page.page, sentence) for page in pages for sentence in split_sentences(page.text)
    ]
    if not page_sentences:
        raise ValueError("PDF에서 문장을 추출하지 못했습니다.")

    vectors = sentence_embedder([sentence for _, sentence in page_sentences])
    if len(vectors) != len(page_sentences):
        raise ValueError("문장 임베딩 개수가 문장 개수와 다릅니다.")

    chunks: list[Chunk] = []
    chunk_no = 0
    chunk_page = page_sentences[0][0]
    pending: list[str] = []
    pending_tokens = 0
    previous_vector: Sequence[float] | None = None

    for index, (page_no, sentence) in enumerate(page_sentences):
        sentence_tokens = len(list(codec.encode(sentence)))
        boundary = bool(pending) and page_no != chunk_page
        if not boundary and pending and previous_vector is not None:
            if _cosine_distance(previous_vector, vectors[index]) > breakpoint_threshold:
                boundary = True
        if not boundary and pending and pending_tokens + sentence_tokens > chunk_size:
            boundary = True
        if boundary:
            chunks.append(
                Chunk(
                    chunk_id=f"p{chunk_page:04d}-c{chunk_no:05d}",
                    page=chunk_page,
                    chunk_no=chunk_no,
                    token_count=pending_tokens,
                    content=" ".join(pending),
                )
            )
            chunk_no += 1
            pending, pending_tokens = [], 0
        chunk_page = page_no
        pending.append(sentence)
        pending_tokens += sentence_tokens
        previous_vector = vectors[index]
    if pending:
        chunks.append(
            Chunk(
                chunk_id=f"p{chunk_page:04d}-c{chunk_no:05d}",
                page=chunk_page,
                chunk_no=chunk_no,
                token_count=pending_tokens,
                content=" ".join(pending),
            )
        )
    if not chunks:
        raise ValueError("PDF에서 의미 청크를 만들지 못했습니다.")
    return chunks


def chunk_pages_parent_child(
    pages: Sequence[PageText],
    *,
    parent_chunk_size: int,
    child_chunk_size: int,
    child_overlap: int,
    codec: TokenCodec,
) -> list[Chunk]:
    """구조적 큰 블록(Parent)을 만들고 그 안을 토큰 윈도우(Child)로 잘게 나눈다.

    임베딩·검색 대상은 Child다. 검색 결과의 context_content/context_token_count에는
    Child가 속한 Parent 전체 텍스트·토큰 수를 담아 Context 예산 비교에 사용한다.
    """

    if child_chunk_size <= 0:
        raise ValueError("child_chunk_size는 1 이상이어야 합니다.")
    if child_overlap < 0 or child_overlap >= child_chunk_size:
        raise ValueError("child_overlap은 0 이상 child_chunk_size 미만이어야 합니다.")

    parents = chunk_pages_structural(pages, chunk_size=parent_chunk_size, codec=codec)
    chunks: list[Chunk] = []
    chunk_no = 0
    for parent in parents:
        for content, token_count in _split_oversized_with_overlap(
            parent.content, child_chunk_size, child_overlap, codec
        ):
            if not content:
                continue
            chunks.append(
                Chunk(
                    chunk_id=f"p{parent.page:04d}-c{chunk_no:05d}",
                    page=parent.page,
                    chunk_no=chunk_no,
                    token_count=token_count,
                    content=content,
                    context_content=parent.content,
                    context_token_count=parent.token_count,
                )
            )
            chunk_no += 1
    if not chunks:
        raise ValueError("PDF에서 Parent-Child 청크를 만들지 못했습니다.")
    return chunks


def _split_oversized_with_overlap(
    text: str, chunk_size: int, overlap: int, codec: TokenCodec
) -> list[tuple[str, int]]:
    splitter = getattr(codec, "split_windows", None)
    if splitter is not None:
        return splitter(text, chunk_size=chunk_size, overlap=overlap)
    tokens = list(codec.encode(text))
    step = chunk_size - overlap
    windows: list[tuple[str, int]] = []
    for start in range(0, len(tokens), step):
        window = tokens[start : start + chunk_size]
        if not window:
            break
        windows.append((codec.decode(window).strip(), len(window)))
        if start + chunk_size >= len(tokens):
            break
    return windows


def _cosine_distance(left: Sequence[float], right: Sequence[float]) -> float:
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        return 1.0
    return 1.0 - dot / (left_norm * right_norm)
