"""Top-K 품질 지표와 booth/agent 격리 검색을 검증한다."""

import unittest

from rag_spike.evaluation import evaluate_reranked_hits, evaluate_retrieval
from rag_spike.models import Chunk, EvalCase, SearchHit, TARGET_DIMENSION
from rag_spike.store import MemoryVectorStore


def vector(first: float, second: float) -> list[float]:
    return [first, second] + [0.0] * (TARGET_DIMENSION - 2)


class EvaluationTest(unittest.TestCase):
    def test_rejects_non_positive_top_k(self) -> None:
        with self.assertRaisesRegex(ValueError, "top_k"):
            evaluate_retrieval(
                store=MemoryVectorStore(),
                run_id="run",
                model_id="model",
                booth_id=1,
                agent_id=1,
                cases=[EvalCase("q", (1,), ())],
                query_vectors=[[1.0, 0.0]],
                top_k=0,
            )

    def test_recall_mrr_and_scope_filter(self) -> None:
        store = MemoryVectorStore()
        store.replace(
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            chunks=[
                Chunk("target", 2, 0, 4, "정답 표식"),
                Chunk("noise", 3, 1, 4, "무관 문장"),
            ],
            vectors=[vector(1, 0), vector(0, 1)],
        )
        store.replace(
            run_id="run",
            model_id="model",
            booth_id=2,
            agent_id=2,
            chunks=[Chunk("foreign", 2, 0, 4, "정답 표식")],
            vectors=[vector(1, 0)],
        )
        metrics = evaluate_retrieval(
            store=store,
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            cases=[EvalCase("q1", "질문", frozenset({2}))],
            query_vectors=[vector(1, 0)],
            top_k=1,
        )
        self.assertEqual(metrics.recall_at_k, 1.0)
        self.assertEqual(metrics.mrr, 1.0)
        self.assertEqual(metrics.ndcg, 1.0)
        self.assertEqual(metrics.mean_context_tokens, 4.0)
        self.assertEqual(metrics.p95_context_tokens, 4)
        self.assertEqual(metrics.missed_case_ids, ())
        self.assertEqual(metrics.leakage_count, 0)

    def test_uses_context_content_and_context_token_count_when_present(self) -> None:
        store = MemoryVectorStore()
        store.replace(
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            chunks=[
                Chunk(
                    "child",
                    2,
                    0,
                    4,
                    "child 검색용 조각",
                    context_content="parent 전체 문맥 안에 정답 표식이 있다",
                    context_token_count=40,
                ),
            ],
            vectors=[vector(1, 0)],
        )
        metrics = evaluate_retrieval(
            store=store,
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            cases=[EvalCase("q1", "질문", frozenset(), ("정답 표식",))],
            query_vectors=[vector(1, 0)],
            top_k=1,
        )
        self.assertEqual(metrics.recall_at_k, 1.0)
        self.assertEqual(metrics.mean_context_tokens, 40.0)

    def test_reports_missed_case_ids_and_context_tokens(self) -> None:
        store = MemoryVectorStore()
        store.replace(
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            chunks=[Chunk("noise", 3, 0, 7, "무관 문장")],
            vectors=[vector(1, 0)],
        )
        metrics = evaluate_retrieval(
            store=store,
            run_id="run",
            model_id="model",
            booth_id=1,
            agent_id=1,
            cases=[EvalCase("q-missed", "질문", frozenset({2}))],
            query_vectors=[vector(1, 0)],
            top_k=1,
        )
        self.assertEqual(metrics.recall_at_k, 0.0)
        self.assertEqual(metrics.mean_context_tokens, 7.0)
        self.assertEqual(metrics.missed_case_ids, ("q-missed",))


def search_hit(chunk_id: str, page: int, content: str = "무관") -> SearchHit:
    return SearchHit(
        chunk_id=chunk_id,
        page=page,
        token_count=5,
        content=content,
        distance=0.0,
        booth_id=1,
        agent_id=1,
    )


class EvaluateRerankedHitsTest(unittest.TestCase):
    def test_perfect_ranking_scores_one_on_every_metric(self) -> None:
        cases = [EvalCase("q1", "질문", frozenset({2}))]
        hit_lists = [[search_hit("target", 2), search_hit("noise", 3)]]

        metrics = evaluate_reranked_hits(cases, hit_lists)

        self.assertEqual(metrics.recall_at_n, 1.0)
        self.assertEqual(metrics.mrr, 1.0)
        self.assertEqual(metrics.ndcg, 1.0)
        self.assertEqual(metrics.missed_case_ids, ())

    def test_relevant_hit_ranked_second_lowers_ndcg_below_mrr_only_penalty(self) -> None:
        cases = [EvalCase("q1", "질문", frozenset({2}))]
        hit_lists = [[search_hit("noise", 3), search_hit("target", 2)]]

        metrics = evaluate_reranked_hits(cases, hit_lists)

        self.assertEqual(metrics.recall_at_n, 1.0)
        self.assertEqual(metrics.mrr, 0.5)
        self.assertAlmostEqual(metrics.ndcg, 0.6309297535714575, places=6)

    def test_missing_relevant_hit_scores_zero_and_is_reported(self) -> None:
        cases = [EvalCase("q-missed", "질문", frozenset({2}))]
        hit_lists = [[search_hit("noise", 3)]]

        metrics = evaluate_reranked_hits(cases, hit_lists)

        self.assertEqual(metrics.recall_at_n, 0.0)
        self.assertEqual(metrics.mrr, 0.0)
        self.assertEqual(metrics.ndcg, 0.0)
        self.assertEqual(metrics.missed_case_ids, ("q-missed",))

    def test_rejects_mismatched_case_and_hit_list_counts(self) -> None:
        with self.assertRaisesRegex(ValueError, "질문 수"):
            evaluate_reranked_hits([EvalCase("q1", "질문", frozenset({1}))], [])


if __name__ == "__main__":
    unittest.main()
