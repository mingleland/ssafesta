"""문서별 튜닝 결과의 가중 집계와 추천 순서를 검증한다."""

import unittest

from rag_spike.tuning import aggregate_reports, render_markdown


def report(document: str, *, recall: float, mrr: float, context_tokens: float) -> dict:
    return {
        "source_pdf_name": document,
        "models": [
            {
                "model_id": "model",
                "embedding_request_count": 2,
                "estimated_gms_credits": 0.02,
                "elapsed_seconds": 3.0,
            }
        ],
        "results": [
            {
                "model_id": "model",
                "chunk_size": 600,
                "overlap_ratio": 0.15,
                "top_k": 5,
                "evaluated_queries": 10,
                "recall_at_k": recall,
                "mrr": mrr,
                "p50_search_ms": 2.0,
                "p95_search_ms": 4.0,
                "mean_context_tokens": context_tokens,
                "p95_context_tokens": context_tokens + 10,
                "leakage_count": 0,
                "missed_case_ids": [] if recall == 1.0 else ["q1"],
            }
        ],
    }


class TuningTest(unittest.TestCase):
    def test_aggregates_weighted_metrics_and_renders_failures(self) -> None:
        summary = aggregate_reports(
            [
                report("a.pdf", recall=1.0, mrr=0.8, context_tokens=100),
                report("b.pdf", recall=0.9, mrr=0.6, context_tokens=200),
            ]
        )
        recommended = summary["recommended"]
        self.assertAlmostEqual(recommended["weighted_recall"], 0.95)
        self.assertAlmostEqual(recommended["weighted_mrr"], 0.7)
        self.assertAlmostEqual(recommended["weighted_mean_context_tokens"], 150)
        self.assertTrue(recommended["quality_gate_passed"])
        self.assertEqual(summary["model_execution_summaries"][0]["embedding_request_count"], 4)
        self.assertIn("`q1`", render_markdown(summary))

    def test_rejects_missing_document_combination(self) -> None:
        second = report("b.pdf", recall=1.0, mrr=1.0, context_tokens=100)
        second["results"][0]["top_k"] = 8
        with self.assertRaisesRegex(ValueError, "누락"):
            aggregate_reports([report("a.pdf", recall=1.0, mrr=1.0, context_tokens=100), second])


if __name__ == "__main__":
    unittest.main()
