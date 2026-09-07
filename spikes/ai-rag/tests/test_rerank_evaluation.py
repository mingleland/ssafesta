"""검색-재정렬 파이프라인 조합(rerank_evaluation)을 검증한다."""

import unittest

from rag_spike.models import Chunk, EvalCase, ModelSpec, TARGET_DIMENSION
from rag_spike.rerank_evaluation import evaluate_rerank_method
from rag_spike.reranker import CrossEncoderReranker, GmsLlmReranker, RerankMethod
from rag_spike.store import MemoryVectorStore


def vector(first: float, second: float) -> list[float]:
    return [first, second] + [0.0] * (TARGET_DIMENSION - 2)


def build_store() -> MemoryVectorStore:
    store = MemoryVectorStore()
    store.replace(
        run_id="run",
        model_id="model",
        booth_id=1,
        agent_id=1,
        chunks=[
            Chunk("near", 2, 0, 4, "near"),
            Chunk("far", 3, 1, 4, "far"),
        ],
        vectors=[vector(1, 0), vector(0, 1)],
    )
    return store


class EvaluateRerankMethodTest(unittest.TestCase):
    def test_none_method_truncates_bi_encoder_order(self) -> None:
        report = evaluate_rerank_method(
            store=build_store(),
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            cases=[EvalCase("q1", "질문", frozenset({2}))],
            query_vectors=[vector(1, 0)],
            retrieval_top_k=2,
            top_n=1,
            method=RerankMethod.NONE,
        )
        self.assertEqual(report["method"], "none")
        self.assertEqual(report["recall_at_n"], 1.0)
        self.assertEqual(report["rerank_failure_count"], 0)

    def test_similarity_cutoff_requires_threshold(self) -> None:
        with self.assertRaisesRegex(ValueError, "similarity_max_distance"):
            evaluate_rerank_method(
                store=build_store(),
                run_id="run",
                model_id="model",
                booth_id=1,
                agent_id=1,
                cases=[EvalCase("q1", "질문", frozenset({2}))],
                query_vectors=[vector(1, 0)],
                retrieval_top_k=2,
                top_n=2,
                method=RerankMethod.SIMILARITY_CUTOFF,
            )

    def test_similarity_cutoff_drops_far_hit(self) -> None:
        report = evaluate_rerank_method(
            store=build_store(),
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            cases=[EvalCase("q1", "질문", frozenset({2}))],
            query_vectors=[vector(1, 0)],
            retrieval_top_k=2,
            top_n=2,
            method=RerankMethod.SIMILARITY_CUTOFF,
            similarity_max_distance=0.5,
        )
        self.assertEqual(report["method"], "similarity_cutoff")
        self.assertEqual(report["mean_context_tokens"], 4.0)

    def test_cross_encoder_requires_instance(self) -> None:
        with self.assertRaisesRegex(ValueError, "cross_encoder"):
            evaluate_rerank_method(
                store=build_store(),
                run_id="run",
                model_id="model",
                booth_id=1,
                agent_id=1,
                cases=[EvalCase("q1", "질문", frozenset({2}))],
                query_vectors=[vector(1, 0)],
                retrieval_top_k=2,
                top_n=1,
                method=RerankMethod.CROSS_ENCODER,
            )

    def test_cross_encoder_success_aggregates_elapsed_and_requests(self) -> None:
        reranker = CrossEncoderReranker(scorer=lambda pairs: [0.1, 0.9])
        report = evaluate_rerank_method(
            store=build_store(),
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            cases=[EvalCase("q1", "질문", frozenset({2}))],
            query_vectors=[vector(1, 0)],
            retrieval_top_k=2,
            top_n=1,
            method=RerankMethod.CROSS_ENCODER,
            cross_encoder=reranker,
        )
        self.assertEqual(report["rerank_request_count"], 1)
        self.assertEqual(report["rerank_failure_count"], 0)
        self.assertGreaterEqual(report["rerank_elapsed_seconds"], 0.0)

    def test_gms_llm_failure_is_counted_and_still_returns_metrics(self) -> None:
        model = ModelSpec(model_id="gpt-4.1-nano", provider="openai", credit_per_request=1.0)

        def poster_factory(_spec: ModelSpec):
            def post(_payload):
                return {"choices": [{"message": {"content": "not json"}}]}

            return post

        reranker = GmsLlmReranker(api_key="test-key", poster_factory=poster_factory)
        report = evaluate_rerank_method(
            store=build_store(),
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            cases=[EvalCase("q1", "질문", frozenset({2}))],
            query_vectors=[vector(1, 0)],
            retrieval_top_k=2,
            top_n=1,
            method=RerankMethod.GMS_LLM,
            gms_llm=reranker,
            llm_model=model,
        )
        self.assertEqual(report["rerank_failure_count"], 1)
        self.assertEqual(report["estimated_rerank_credits"], 1.0)
        # 실패해도 bi-encoder 순서로 fallback해서 평가 자체는 계속된다
        self.assertEqual(report["recall_at_n"], 1.0)


if __name__ == "__main__":
    unittest.main()
