"""LLM Judge 자동 채점기가 5축 점수를 정확히 파싱·집계하는지 검증한다 (S15P21A604-372).

사람 채점(체크박스)을 대체하는 optional 경로 — Jira 티켓이 "사람 평가 또는
LLM Judge"를 동등하게 인정하므로, 채점 실패는 조용히 삼키지 않고
JudgeVerdict.success=False로 드러낸다 (gms_chat.py와 같은 원칙).
"""

import unittest

from rag_spike.gms_chat import GmsChatClient
from rag_spike.judge import LLMJudge, score_report
from rag_spike.models import ModelSpec

JUDGE_MODEL = ModelSpec(model_id="gpt-4.1-mini", provider="openai", credit_per_request=4.0)


def _client_returning(text: str) -> GmsChatClient:
    def poster_factory(spec: ModelSpec):
        def post(payload):
            return {"choices": [{"message": {"content": text}}]}

        return post

    return GmsChatClient(api_key="test-key", poster_factory=poster_factory)


def _client_raising_http_error() -> GmsChatClient:
    def poster_factory(spec: ModelSpec):
        def post(payload):
            raise RuntimeError("network down")

        return post

    return GmsChatClient(api_key="test-key", poster_factory=poster_factory)


class LLMJudgeScoreTest(unittest.TestCase):
    def test_parses_clean_json_verdict(self) -> None:
        client = _client_returning(
            '{"groundedness": 2, "relevance": 2, "no_hallucination": 1, '
            '"rejection_appropriate": 2, "naturalness": 2, "reasoning": "근거 확인됨"}'
        )
        judge = LLMJudge(client=client, judge_model=JUDGE_MODEL)

        verdict = judge.score(
            query="운영 시간은?",
            gold_answer_status="ANSWERABLE",
            gold_answer="오전 10시부터 오후 6시까지입니다.",
            candidate_answer="오전 10시부터 오후 6시까지 운영합니다.",
        )

        self.assertTrue(verdict.success)
        self.assertEqual(verdict.scores.groundedness, 2)
        self.assertEqual(verdict.scores.no_hallucination, 1)
        self.assertEqual(verdict.scores.total, 9)
        self.assertEqual(verdict.scores.reasoning, "근거 확인됨")

    def test_parses_json_embedded_in_surrounding_prose(self) -> None:
        # 모델이 지시를 어기고 JSON 앞뒤에 설명을 덧붙이는 경우도 흔하다.
        client = _client_returning(
            '채점 결과입니다:\n'
            '{"groundedness": 1, "relevance": 1, "no_hallucination": 1, '
            '"rejection_appropriate": 1, "naturalness": 1, "reasoning": "보통"}\n'
            '이상입니다.'
        )
        judge = LLMJudge(client=client, judge_model=JUDGE_MODEL)

        verdict = judge.score(
            query="q", gold_answer_status="ANSWERABLE", gold_answer="g", candidate_answer="a"
        )

        self.assertTrue(verdict.success)
        self.assertEqual(verdict.scores.total, 5)

    def test_rejects_out_of_range_axis_value(self) -> None:
        client = _client_returning(
            '{"groundedness": 3, "relevance": 2, "no_hallucination": 2, '
            '"rejection_appropriate": 2, "naturalness": 2, "reasoning": "x"}'
        )
        judge = LLMJudge(client=client, judge_model=JUDGE_MODEL)

        verdict = judge.score(
            query="q", gold_answer_status="ANSWERABLE", gold_answer="g", candidate_answer="a"
        )

        self.assertFalse(verdict.success)
        self.assertIsNone(verdict.scores)
        self.assertIn("groundedness", verdict.error)

    def test_rejects_response_with_no_json_object(self) -> None:
        client = _client_returning("죄송하지만 채점할 수 없습니다.")
        judge = LLMJudge(client=client, judge_model=JUDGE_MODEL)

        verdict = judge.score(
            query="q", gold_answer_status="ANSWERABLE", gold_answer="g", candidate_answer="a"
        )

        self.assertFalse(verdict.success)
        self.assertIsNotNone(verdict.error)

    def test_empty_candidate_answer_fails_without_calling_client(self) -> None:
        calls = []

        def poster_factory(spec: ModelSpec):
            def post(payload):
                calls.append(payload)
                return {"choices": [{"message": {"content": "{}"}}]}

            return post

        client = GmsChatClient(api_key="test-key", poster_factory=poster_factory)
        judge = LLMJudge(client=client, judge_model=JUDGE_MODEL)

        verdict = judge.score(
            query="q", gold_answer_status="ANSWERABLE", gold_answer="g", candidate_answer="   "
        )

        self.assertFalse(verdict.success)
        self.assertEqual(calls, [])

    def test_provider_failure_surfaces_as_unsuccessful_verdict(self) -> None:
        client = _client_raising_http_error()
        judge = LLMJudge(client=client, judge_model=JUDGE_MODEL)

        verdict = judge.score(
            query="q", gold_answer_status="ANSWERABLE", gold_answer="g", candidate_answer="a"
        )

        self.assertFalse(verdict.success)
        self.assertIsNotNone(verdict.error)


class ScoreReportTest(unittest.TestCase):
    def test_skips_failed_generations_and_aggregates_per_case_and_model(self) -> None:
        client = _client_returning(
            '{"groundedness": 2, "relevance": 2, "no_hallucination": 2, '
            '"rejection_appropriate": 2, "naturalness": 2, "reasoning": "ok"}'
        )
        judge = LLMJudge(client=client, judge_model=JUDGE_MODEL)
        report = {
            "questions": [
                {
                    "case_id": "q01",
                    "query": "질문1",
                    "gold_answer_status": "ANSWERABLE",
                    "gold_answer": "정답1",
                    "model_answers": {
                        "gpt-4.1-mini": {
                            "answer": "실제 답변",
                            "success": True,
                            "error": None,
                        },
                        "gpt-5.4-nano": {
                            "answer": "",
                            "success": False,
                            "error": "GMS Chat 요청 실패",
                        },
                    },
                }
            ]
        }

        results = score_report(report, judge)

        self.assertEqual(results["q01"]["gpt-4.1-mini"]["success"], True)
        self.assertEqual(results["q01"]["gpt-4.1-mini"]["total"], 10)
        self.assertEqual(results["q01"]["gpt-5.4-nano"]["success"], False)
        self.assertEqual(results["q01"]["gpt-5.4-nano"]["error"], "GMS Chat 요청 실패")

    def test_missing_error_field_falls_back_to_default_message(self) -> None:
        judge = LLMJudge(client=_client_returning("{}"), judge_model=JUDGE_MODEL)
        report = {
            "questions": [
                {
                    "case_id": "q01",
                    "query": "질문1",
                    "gold_answer_status": "ANSWERABLE",
                    "gold_answer": "정답1",
                    "model_answers": {
                        "gpt-5.4-nano": {"answer": "", "success": False, "error": None}
                    },
                }
            ]
        }

        results = score_report(report, judge)

        self.assertEqual(results["q01"]["gpt-5.4-nano"]["error"], "생성 실패로 채점 생략")


if __name__ == "__main__":
    unittest.main()
