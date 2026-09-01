"""수동 검토 Markdown 렌더링을 검증한다."""

import json
import tempfile
import unittest
from pathlib import Path

from rag_spike.review_markdown import render_gold_file, render_review_markdown


class ReviewMarkdownTest(unittest.TestCase):
    def test_renders_question_and_model_candidate(self) -> None:
        report = {
            "document": "sample.md",
            "query_count": 1,
            "chunk_count": 1,
            "chunk_size": 600,
            "overlap_ratio": 0.15,
            "top_k": 1,
            "models": [
                {
                    "model_id": "model",
                    "document_embedding_seconds": 1.0,
                    "query_embedding_seconds": 1.0,
                    "search_seconds": 0.1,
                    "request_count": 2,
                    "estimated_gms_credits": 0.1,
                    "elapsed_seconds": 2.1,
                }
            ],
            "questions": [
                {
                    "query": "서비스가 무엇인가요?",
                    "suggested_relevant_contains": ["서비스 설명 문장입니다."],
                    "model_results": {
                        "model": [
                            {
                                "rank": 1,
                                "chunk_id": "c1",
                                "distance": 0.2,
                                "content": "서비스 설명 문장입니다.",
                            }
                        ]
                    },
                }
            ],
        }
        rendered = render_review_markdown(report, "샘플")
        self.assertIn("서비스가 무엇인가요?", rendered)
        self.assertIn("모델별 Top-1", rendered)
        self.assertIn("NO_ANSWER", rendered)

    def test_renders_gold_labels(self) -> None:
        row = {
            "id": "q1",
            "query": "질문",
            "answer_status": "ANSWERABLE",
            "answer": "답변",
            "relevant_contains": ["정답 문장"],
            "rationale": "원문에 명시됨",
        }
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "gold.jsonl"
            output = Path(directory) / "gold.md"
            source.write_text(json.dumps(row, ensure_ascii=False), encoding="utf-8")
            render_gold_file(source, output, "샘플")
            rendered = output.read_text(encoding="utf-8")
        self.assertIn("ANSWERABLE: 1개", rendered)
        self.assertIn("정답 문장", rendered)


if __name__ == "__main__":
    unittest.main()
