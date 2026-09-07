"""청킹 전략 비교 결과의 가중 집계와 추천 순서를 검증한다."""

import unittest

from rag_spike.strategy_comparison import aggregate_reports, render_markdown


def report(document: str, strategy: str, *, recall: float, mrr: float, context_tokens: float) -> dict:
    return {
        "source_pdf_name": document,
        "model": {
            "model_id": "text-embedding-3-large",
            "embedding_request_count": 3,
            "boundary_embedding_request_count": 1 if strategy == "semantic" else 0,
            "estimated_gms_credits": 0.03,
        },
        "results": [
            {
                "strategy": strategy,
                "top_k": 10,
                "chunk_count": 5,
                "evaluated_queries": 10,
                "recall_at_k": recall,
                "mrr": mrr,
                "p95_search_ms": 4.0,
                "mean_context_tokens": context_tokens,
                "p95_context_tokens": context_tokens + 10,
                "leakage_count": 0,
                "missed_case_ids": [] if recall == 1.0 else ["q1"],
            }
        ],
    }


class StrategyComparisonTest(unittest.TestCase):
    def test_aggregates_weighted_metrics_and_ranks_strategies(self) -> None:
        summary = aggregate_reports(
            [
                report("a.pdf", "fixed", recall=0.9, mrr=0.8, context_tokens=900),
                report("b.pdf", "fixed", recall=0.9, mrr=0.8, context_tokens=900),
                report("a.pdf", "parent_child", recall=1.0, mrr=0.9, context_tokens=300),
                report("b.pdf", "parent_child", recall=1.0, mrr=0.9, context_tokens=300),
            ]
        )
        recommended = summary["recommended"]
        self.assertEqual(recommended["strategy"], "parent_child")
        self.assertTrue(recommended["quality_gate_passed"])
        self.assertEqual(summary["embedding_request_count"], 12)
        self.assertIn("`q1`", render_markdown(summary))

    def test_rejects_missing_document_combination(self) -> None:
        with self.assertRaisesRegex(ValueError, "누락"):
            aggregate_reports(
                [
                    report("a.pdf", "fixed", recall=1.0, mrr=1.0, context_tokens=900),
                    report("b.pdf", "fixed", recall=1.0, mrr=1.0, context_tokens=900),
                    report("a.pdf", "semantic", recall=1.0, mrr=1.0, context_tokens=500),
                ]
            )


if __name__ == "__main__":
    unittest.main()
