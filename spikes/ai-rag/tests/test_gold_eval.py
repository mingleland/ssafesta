"""Sol 골드 라벨에서 NO_ANSWER 제외와 정답 질문 로딩을 검증한다."""

import json
import tempfile
import unittest
from pathlib import Path

from rag_spike.benchmark import load_eval_cases


class GoldEvalTest(unittest.TestCase):
    def test_skips_no_answer_and_loads_relevant_case(self) -> None:
        rows = [
            {
                "id": "q1",
                "query": "답이 있는 질문",
                "answer_status": "ANSWERABLE",
                "relevant_contains": ["정답 문장"],
            },
            {
                "id": "q2",
                "query": "답이 없는 질문",
                "answer_status": "NO_ANSWER",
                "relevant_contains": [],
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gold.jsonl"
            path.write_text(
                "\n".join(json.dumps(row, ensure_ascii=False) for row in rows),
                encoding="utf-8",
            )
            cases = load_eval_cases(path)
        self.assertEqual([case.case_id for case in cases], ["q1"])


if __name__ == "__main__":
    unittest.main()
