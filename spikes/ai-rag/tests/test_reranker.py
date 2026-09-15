"""유사도 절단·Cross-Encoder·GMS LLM reranker의 재정렬과 실패 처리(fallback)를 검증한다."""

import unittest

from rag_spike.models import ModelSpec
from rag_spike.reranker import (
    CrossEncoderReranker,
    GmsLlmReranker,
    RerankMethod,
    cutoff_by_similarity,
    truncate_top_n,
)
from rag_spike.models import SearchHit


def hit(chunk_id: str, distance: float, content: str = "내용") -> SearchHit:
    return SearchHit(
        chunk_id=chunk_id,
        page=1,
        token_count=10,
        content=content,
        distance=distance,
        booth_id=1,
        agent_id=1,
    )


class TruncateTopNTest(unittest.TestCase):
    def test_keeps_only_first_n(self) -> None:
        hits = [hit("a", 0.1), hit("b", 0.2), hit("c", 0.3)]
        self.assertEqual([h.chunk_id for h in truncate_top_n(hits, 2)], ["a", "b"])

    def test_rejects_non_positive_top_n(self) -> None:
        with self.assertRaisesRegex(ValueError, "top_n"):
            truncate_top_n([hit("a", 0.1)], 0)


class CutoffBySimilarityTest(unittest.TestCase):
    def test_keeps_hits_within_threshold_and_drops_the_rest(self) -> None:
        hits = [hit("a", 0.1), hit("b", 0.4), hit("c", 0.9)]
        result = cutoff_by_similarity(hits, max_distance=0.5)
        self.assertEqual([h.chunk_id for h in result], ["a", "b"])

    def test_rejects_negative_threshold(self) -> None:
        with self.assertRaisesRegex(ValueError, "max_distance"):
            cutoff_by_similarity([hit("a", 0.1)], max_distance=-0.1)


class CrossEncoderRerankerTest(unittest.TestCase):
    def test_reorders_hits_by_descending_score_and_truncates(self) -> None:
        hits = [hit("a", 0.1, content="a"), hit("b", 0.2, content="b"), hit("c", 0.3, content="c")]

        def scorer(pairs):
            scores = {"a": 0.2, "b": 0.9, "c": 0.5}
            return [scores[content] for _, content in pairs]

        reranker = CrossEncoderReranker(scorer=scorer)

        result = reranker.rerank(query="질문", hits=hits, top_n=2)

        self.assertTrue(result.success)
        self.assertEqual([h.chunk_id for h in result.hits], ["b", "c"])
        self.assertEqual(result.method, RerankMethod.CROSS_ENCODER)

    def test_scorer_failure_falls_back_to_original_order_and_reports_error(self) -> None:
        hits = [hit("a", 0.1, content="a"), hit("b", 0.2, content="b")]

        def failing_scorer(pairs):
            raise RuntimeError("모델 로딩 실패")

        reranker = CrossEncoderReranker(scorer=failing_scorer)

        result = reranker.rerank(query="질문", hits=hits, top_n=2)

        self.assertFalse(result.success)
        self.assertIsNotNone(result.error)
        self.assertEqual([h.chunk_id for h in result.hits], ["a", "b"])

    def test_mismatched_score_count_is_reported_as_failure(self) -> None:
        hits = [hit("a", 0.1, content="a"), hit("b", 0.2, content="b")]

        def bad_scorer(pairs):
            return [0.5]

        reranker = CrossEncoderReranker(scorer=bad_scorer)

        result = reranker.rerank(query="질문", hits=hits, top_n=2)

        self.assertFalse(result.success)
        self.assertEqual([h.chunk_id for h in result.hits], ["a", "b"])


class GmsLlmRerankerTest(unittest.TestCase):
    def test_openai_provider_reorders_by_parsed_json_array(self) -> None:
        hits = [hit("a", 0.1, content="a"), hit("b", 0.2, content="b"), hit("c", 0.3, content="c")]
        model = ModelSpec(model_id="gpt-4.1-nano", provider="openai", credit_per_request=1.0)
        captured_payloads = []

        def poster_factory(spec: ModelSpec):
            def post(payload):
                captured_payloads.append(payload)
                return {
                    "choices": [
                        {"message": {"content": '["c", "a", "b"]'}}
                    ]
                }

            return post

        reranker = GmsLlmReranker(api_key="test-key", poster_factory=poster_factory)

        result = reranker.rerank(query="질문", hits=hits, top_n=2, model=model)

        self.assertTrue(result.success)
        self.assertEqual([h.chunk_id for h in result.hits], ["c", "a"])
        self.assertEqual(result.estimated_credits, 1.0)
        self.assertEqual(len(captured_payloads), 1)
        self.assertEqual(captured_payloads[0]["model"], "gpt-4.1-nano")

    def test_gemini_provider_reorders_by_parsed_json_array(self) -> None:
        hits = [hit("a", 0.1, content="a"), hit("b", 0.2, content="b")]
        model = ModelSpec(model_id="gemini-2.5-flash-lite", provider="gemini", credit_per_request=1.0)

        def poster_factory(spec: ModelSpec):
            def post(payload):
                return {
                    "candidates": [
                        {"content": {"parts": [{"text": '["b", "a"]'}]}}
                    ]
                }

            return post

        reranker = GmsLlmReranker(api_key="test-key", poster_factory=poster_factory)

        result = reranker.rerank(query="질문", hits=hits, top_n=2, model=model)

        self.assertTrue(result.success)
        self.assertEqual([h.chunk_id for h in result.hits], ["b", "a"])

    def test_malformed_response_falls_back_and_reports_error(self) -> None:
        hits = [hit("a", 0.1, content="a"), hit("b", 0.2, content="b")]
        model = ModelSpec(model_id="gpt-4.1-nano", provider="openai", credit_per_request=1.0)

        def poster_factory(spec: ModelSpec):
            def post(payload):
                return {"choices": [{"message": {"content": "이건 JSON이 아님"}}]}

            return post

        reranker = GmsLlmReranker(api_key="test-key", poster_factory=poster_factory)

        result = reranker.rerank(query="질문", hits=hits, top_n=2, model=model)

        self.assertFalse(result.success)
        self.assertIsNotNone(result.error)
        self.assertEqual([h.chunk_id for h in result.hits], ["a", "b"])

    def test_unknown_chunk_id_in_response_falls_back_and_reports_error(self) -> None:
        hits = [hit("a", 0.1, content="a"), hit("b", 0.2, content="b")]
        model = ModelSpec(model_id="gpt-4.1-nano", provider="openai", credit_per_request=1.0)

        def poster_factory(spec: ModelSpec):
            def post(payload):
                return {"choices": [{"message": {"content": '["a", "zzz"]'}}]}

            return post

        reranker = GmsLlmReranker(api_key="test-key", poster_factory=poster_factory)

        result = reranker.rerank(query="질문", hits=hits, top_n=2, model=model)

        self.assertFalse(result.success)
        self.assertEqual([h.chunk_id for h in result.hits], ["a", "b"])

    def test_rejects_empty_api_key(self) -> None:
        with self.assertRaisesRegex(ValueError, "GMS_API_KEY"):
            GmsLlmReranker(api_key="  ")


if __name__ == "__main__":
    unittest.main()
