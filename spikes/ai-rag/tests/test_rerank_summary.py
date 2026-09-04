"""세 문서의 reranker 비교 결과 가중 집계와 추천 순서를 검증한다."""

import unittest

from rag_spike.rerank_summary import aggregate_reports, render_markdown


def report(document: str, *, method: str, recall: float, mrr: float, ndcg: float, **extra) -> dict:
    row = {
        "method": method,
        "retrieval_top_k": 10,
        "top_n": 5,
        "rerank_elapsed_seconds": 0.1,
        "rerank_request_count": 10,
        "estimated_rerank_credits": 0.0,
        "rerank_failure_count": 0,
        "evaluated_queries": 10,
        "recall_at_n": recall,
        "mrr": mrr,
        "ndcg": ndcg,
        "mean_context_tokens": 100.0,
        "p95_context_tokens": 120.0,
        "missed_case_ids": [] if recall == 1.0 else ["q1"],
        **extra,
    }
    return {"source_pdf_name": document, "results": [row]}


class RerankSummaryTest(unittest.TestCase):
    def test_recommends_highest_weighted_recall_then_ndcg(self) -> None:
        summary = aggregate_reports(
            [
                report("a.pdf", method="none", recall=0.8, mrr=0.7, ndcg=0.75),
                report("b.pdf", method="none", recall=0.8, mrr=0.7, ndcg=0.75),
            ]
            + [
                report("a.pdf", method="cross_encoder", recall=1.0, mrr=0.9, ndcg=0.95),
                report("b.pdf", method="cross_encoder", recall=1.0, mrr=0.9, ndcg=0.95),
            ]
        )
        recommended = summary["recommended"]
        self.assertEqual(recommended["method"], "cross_encoder")
        self.assertAlmostEqual(recommended["weighted_recall"], 1.0)
        self.assertAlmostEqual(recommended["weighted_ndcg"], 0.95)

    def test_groups_gms_llm_rows_separately_per_model_variant(self) -> None:
        summary = aggregate_reports(
            [
                report(
                    "a.pdf",
                    method="gms_llm",
                    recall=1.0,
                    mrr=1.0,
                    ndcg=1.0,
                    llm_model_id="gpt-4.1-nano",
                ),
                report(
                    "a.pdf",
                    method="gms_llm",
                    recall=0.5,
                    mrr=0.5,
                    ndcg=0.5,
                    llm_model_id="gemini-2.5-flash-lite",
                ),
            ]
        )
        variants = {candidate["variant"] for candidate in summary["candidates"]}
        self.assertEqual(variants, {"gpt-4.1-nano", "gemini-2.5-flash-lite"})

    def test_rejects_missing_document_combination(self) -> None:
        second = report("b.pdf", method="none", recall=1.0, mrr=1.0, ndcg=1.0)
        second["results"][0]["top_n"] = 8
        with self.assertRaisesRegex(ValueError, "누락"):
            aggregate_reports(
                [report("a.pdf", method="none", recall=1.0, mrr=1.0, ndcg=1.0), second]
            )

    def test_render_markdown_includes_missed_case_ids(self) -> None:
        summary = aggregate_reports(
            [
                report("a.pdf", method="none", recall=0.5, mrr=0.5, ndcg=0.5),
                report("b.pdf", method="none", recall=0.5, mrr=0.5, ndcg=0.5),
            ]
        )
        self.assertIn("`q1`", render_markdown(summary))


if __name__ == "__main__":
    unittest.main()
