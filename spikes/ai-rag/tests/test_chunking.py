"""청킹 경계와 페이지 추적 회귀 테스트다."""

import unittest

from rag_spike.chunking import chunk_pages
from rag_spike.chunking import TikTokenCodec
from rag_spike.models import PageText


class WordCodec:
    def encode(self, text: str) -> list[str]:
        return text.split()

    def decode(self, tokens: list[str]) -> str:
        return " ".join(tokens)


class ChunkingTest(unittest.TestCase):
    def test_preserves_page_and_overlap(self) -> None:
        chunks = chunk_pages(
            [PageText(page=7, text="하나 둘 셋 넷 다섯 여섯")],
            chunk_size=4,
            overlap=2,
            codec=WordCodec(),
        )
        self.assertEqual([chunk.page for chunk in chunks], [7, 7])
        self.assertEqual(chunks[0].content, "하나 둘 셋 넷")
        self.assertEqual(chunks[1].content, "셋 넷 다섯 여섯")

    def test_rejects_invalid_overlap(self) -> None:
        with self.assertRaises(ValueError):
            chunk_pages(
                [PageText(page=1, text="내용")],
                chunk_size=4,
                overlap=4,
                codec=WordCodec(),
            )

    def test_tiktoken_windows_do_not_break_korean_characters(self) -> None:
        chunks = chunk_pages(
            [PageText(page=1, text="한글 테스트 문장을 여러 번 반복합니다. 한글 경계를 보존합니다.")],
            chunk_size=5,
            overlap=2,
            codec=TikTokenCodec(),
        )
        self.assertTrue(chunks)
        self.assertFalse(any("\ufffd" in chunk.content for chunk in chunks))


if __name__ == "__main__":
    unittest.main()
