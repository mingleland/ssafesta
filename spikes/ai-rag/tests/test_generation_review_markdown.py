"""LLM 답변 생성 비교 사람 채점용 Markdown 렌더링을 검증한다."""

import unittest

from rag_spike.generation_review_markdown import render_generation_review


class GenerationReviewMarkdownTest(unittest.TestCase):
    def test_renders_gold_answer_and_model_row_with_scoring_checkboxes(self) -> None:
        report = {
            "document": "sample.md",
            "case_count": 1,
            "models": [
                {
                    "model_id": "gpt-4.1-nano",
                    "request_count": 1,
                    "estimated_credits": 1.0,
                    "elapsed_seconds": 0.8,
                    "failure_count": 0,
                }
            ],
            "questions": [
                {
                    "case_id": "q1",
                    "query": "운영 시간은?",
                    "gold_answer_status": "ANSWERABLE",
                    "gold_answer": "오전 10시부터 오후 6시까지입니다.",
                    "model_answers": {
                        "gpt-4.1-nano": {
                            "answer": "오전 10시부터입니다.",
                            "elapsed_seconds": 0.8,
                            "estimated_credits": 1.0,
                            "success": True,
                            "error": None,
                        }
                    },
                }
            ],
        }
        rendered = render_generation_review(report, "샘플")

        self.assertIn("운영 시간은?", rendered)
        self.assertIn("ANSWERABLE", rendered)
        self.assertIn("오전 10시부터 오후 6시까지입니다.", rendered)
        self.assertIn("gpt-4.1-nano", rendered)
        self.assertIn("오전 10시부터입니다.", rendered)
        self.assertIn("Groundedness", rendered)
        self.assertIn("[ ]", rendered)

    def test_marks_failed_generation_distinctly(self) -> None:
        report = {
            "document": "sample.md",
            "case_count": 1,
            "models": [
                {
                    "model_id": "gpt-4.1-nano",
                    "request_count": 1,
                    "estimated_credits": 1.0,
                    "elapsed_seconds": 0.1,
                    "failure_count": 1,
                }
            ],
            "questions": [
                {
                    "case_id": "q1",
                    "query": "질문",
                    "gold_answer_status": "NO_ANSWER",
                    "gold_answer": "문서에 근거가 없습니다.",
                    "model_answers": {
                        "gpt-4.1-nano": {
                            "answer": "",
                            "elapsed_seconds": 0.1,
                            "estimated_credits": 1.0,
                            "success": False,
                            "error": "timeout",
                        }
                    },
                }
            ],
        }
        rendered = render_generation_review(report, "샘플")
        self.assertIn("실패", rendered)
        self.assertIn("timeout", rendered)


if __name__ == "__main__":
    unittest.main()
