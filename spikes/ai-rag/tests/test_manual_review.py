"""수동 검토 질문과 relevant_contains 후보 생성을 검증한다."""

import json
import tempfile
import unittest
from pathlib import Path

from rag_spike.manual_review import _candidate_snippets, load_queries


class ManualReviewTest(unittest.TestCase):
    def test_loads_query_only_jsonl(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "queries.jsonl"
            path.write_text(
                json.dumps({"id": "q-1", "query": "서비스가 무엇인가요?"}, ensure_ascii=False),
                encoding="utf-8",
            )
            self.assertEqual(load_queries(path)[0]["id"], "q-1")

    def test_candidate_snippets_are_deduplicated(self) -> None:
        hits = [
            {"content": "## 소개\n사용자가 장소와 맥락을 함께 저장합니다."},
            {"content": "## 소개\n사용자가 장소와 맥락을 함께 저장합니다."},
        ]
        self.assertEqual(
            _candidate_snippets(hits, "장소와 맥락은 어떻게 저장하나요?"),
            ["사용자가 장소와 맥락을 함께 저장합니다."],
        )


if __name__ == "__main__":
    unittest.main()
