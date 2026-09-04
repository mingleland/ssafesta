"""청킹 경계와 페이지 추적 회귀 테스트다."""

import unittest

from rag_spike.chunking import (
    TikTokenCodec,
    chunk_pages,
    chunk_pages_parent_child,
    chunk_pages_semantic,
    chunk_pages_structural,
    split_paragraphs,
    split_sentences,
)
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


class StructuralChunkingTest(unittest.TestCase):
    def test_keeps_paragraphs_intact_and_groups_up_to_chunk_size(self) -> None:
        text = "하나 둘\n\n셋 넷\n\n다섯 여섯 일곱 여덟"
        chunks = chunk_pages_structural(
            [PageText(page=1, text=text)], chunk_size=4, codec=WordCodec()
        )
        self.assertEqual([chunk.content for chunk in chunks], ["하나 둘\n\n셋 넷", "다섯 여섯 일곱 여덟"])

    def test_forces_split_of_a_single_oversized_paragraph(self) -> None:
        chunks = chunk_pages_structural(
            [PageText(page=1, text="하나 둘 셋 넷 다섯 여섯")], chunk_size=4, codec=WordCodec()
        )
        self.assertEqual([chunk.content for chunk in chunks], ["하나 둘 셋 넷", "다섯 여섯"])

    def test_rejects_non_positive_chunk_size(self) -> None:
        with self.assertRaises(ValueError):
            chunk_pages_structural([PageText(page=1, text="내용")], chunk_size=0, codec=WordCodec())


class SemanticChunkingTest(unittest.TestCase):
    def test_cuts_at_high_distance_boundary(self) -> None:
        sentences_by_call: list[list[str]] = []

        def embedder(sentences: list[str]) -> list[list[float]]:
            sentences_by_call.append(sentences)
            vectors = {
                "주제 A 첫 문장.": [1.0, 0.0],
                "주제 A 이어지는 문장.": [0.9, 0.1],
                "전혀 다른 주제 문장.": [0.0, 1.0],
            }
            return [vectors[sentence] for sentence in sentences]

        chunks = chunk_pages_semantic(
            [PageText(page=1, text="주제 A 첫 문장. 주제 A 이어지는 문장. 전혀 다른 주제 문장.")],
            chunk_size=100,
            codec=WordCodec(),
            sentence_embedder=embedder,
            breakpoint_threshold=0.3,
        )
        self.assertEqual(len(chunks), 2)
        self.assertIn("주제 A", chunks[0].content)
        self.assertIn("전혀 다른", chunks[1].content)
        self.assertEqual(len(sentences_by_call), 1)

    def test_page_boundary_always_splits(self) -> None:
        def embedder(sentences: list[str]) -> list[list[float]]:
            return [[1.0, 0.0] for _ in sentences]

        chunks = chunk_pages_semantic(
            [
                PageText(page=1, text="첫 페이지 문장."),
                PageText(page=2, text="둘째 페이지 문장."),
            ],
            chunk_size=100,
            codec=WordCodec(),
            sentence_embedder=embedder,
            breakpoint_threshold=0.99,
        )
        self.assertEqual([chunk.page for chunk in chunks], [1, 2])

    def test_chunk_size_cap_forces_split_even_without_distance_boundary(self) -> None:
        def embedder(sentences: list[str]) -> list[list[float]]:
            return [[1.0, 0.0] for _ in sentences]

        chunks = chunk_pages_semantic(
            [PageText(page=1, text="가 나. 다 라. 마 바.")],
            chunk_size=4,
            codec=WordCodec(),
            sentence_embedder=embedder,
            breakpoint_threshold=0.99,
        )
        self.assertGreater(len(chunks), 1)

    def test_rejects_embedder_output_count_mismatch(self) -> None:
        with self.assertRaises(ValueError):
            chunk_pages_semantic(
                [PageText(page=1, text="가 나. 다 라.")],
                chunk_size=100,
                codec=WordCodec(),
                sentence_embedder=lambda sentences: [[1.0, 0.0]],
            )


class ParentChildChunkingTest(unittest.TestCase):
    def test_children_carry_parent_as_context(self) -> None:
        text = "하나 둘 셋 넷 다섯 여섯 일곱 여덟"
        chunks = chunk_pages_parent_child(
            [PageText(page=1, text=text)],
            parent_chunk_size=8,
            child_chunk_size=4,
            child_overlap=0,
            codec=WordCodec(),
        )
        self.assertEqual([chunk.content for chunk in chunks], ["하나 둘 셋 넷", "다섯 여섯 일곱 여덟"])
        for chunk in chunks:
            self.assertEqual(chunk.context_content, text)
            self.assertEqual(chunk.context_token_count, 8)

    def test_rejects_invalid_child_overlap(self) -> None:
        with self.assertRaises(ValueError):
            chunk_pages_parent_child(
                [PageText(page=1, text="내용")],
                parent_chunk_size=8,
                child_chunk_size=4,
                child_overlap=4,
                codec=WordCodec(),
            )


class SentenceAndParagraphSplitTest(unittest.TestCase):
    def test_split_paragraphs_drops_blank_blocks(self) -> None:
        self.assertEqual(split_paragraphs("하나\n\n\n둘"), ["하나", "둘"])

    def test_split_sentences_separates_on_terminal_punctuation(self) -> None:
        self.assertEqual(split_sentences("가 나. 다 라."), ["가 나.", "다 라."])


if __name__ == "__main__":
    unittest.main()
