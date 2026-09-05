"""Chunk·SearchHit의 context_content/context_token_count 기본값 채움을 검증한다."""

import unittest

from rag_spike.models import Chunk, SearchHit


class ChunkDefaultsTest(unittest.TestCase):
    def test_defaults_context_fields_to_content_when_omitted(self) -> None:
        chunk = Chunk("id", 1, 0, 4, "본문")
        self.assertEqual(chunk.context_content, "본문")
        self.assertEqual(chunk.context_token_count, 4)

    def test_keeps_explicit_context_fields_when_provided(self) -> None:
        chunk = Chunk("id", 1, 0, 4, "child", context_content="parent", context_token_count=40)
        self.assertEqual(chunk.context_content, "parent")
        self.assertEqual(chunk.context_token_count, 40)


class SearchHitDefaultsTest(unittest.TestCase):
    def test_defaults_context_fields_to_content_when_omitted(self) -> None:
        hit = SearchHit("id", 1, 4, "본문", 0.1, 1, 1)
        self.assertEqual(hit.context_content, "본문")
        self.assertEqual(hit.context_token_count, 4)


if __name__ == "__main__":
    unittest.main()
