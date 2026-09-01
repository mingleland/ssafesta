"""GMS provider별 요청·응답 형식과 차원 불일치 실패를 검증한다."""

import unittest

from rag_spike.embedding import DimensionMismatchError, GmsEmbeddingClient
from rag_spike.models import MODEL_SPECS, TARGET_DIMENSION


class EmbeddingClientTest(unittest.TestCase):
    def test_openai_batches_inputs_and_requests_1536_dimensions(self) -> None:
        payloads: list[dict] = []

        def openai_poster(payload: dict) -> dict:
            payloads.append(payload)
            return {
                "data": [
                    {"index": index, "embedding": [float(index)] * TARGET_DIMENSION}
                    for index, _ in enumerate(payload["input"])
                ]
            }

        client = GmsEmbeddingClient(
            api_key="fake-for-test",
            batch_size=2,
            openai_poster=openai_poster,
            gemini_poster=lambda _: {},
        )
        result = client.embed(["질문 1", "질문 2"], MODEL_SPECS["text-embedding-3-large"])
        self.assertEqual(payloads[0]["model"], "text-embedding-3-large")
        self.assertEqual(payloads[0]["input"], ["질문 1", "질문 2"])
        self.assertEqual(payloads[0]["dimensions"], TARGET_DIMENSION)
        self.assertEqual(result.request_count, 1)
        self.assertEqual(len(result.vectors), 2)

    def test_gemini_uses_embed_content_shape_and_single_requests(self) -> None:
        payloads: list[dict] = []

        def gemini_poster(payload: dict) -> dict:
            payloads.append(payload)
            return {"embedding": {"values": [0.0] * TARGET_DIMENSION}}

        client = GmsEmbeddingClient(
            api_key="fake-for-test",
            openai_poster=lambda _: {},
            gemini_poster=gemini_poster,
        )
        result = client.embed(["질문 1", "질문 2"], MODEL_SPECS["gemini-embedding-2"])
        self.assertEqual(payloads[0]["content"], {"parts": [{"text": "질문 1"}]})
        self.assertEqual(payloads[0]["outputDimensionality"], TARGET_DIMENSION)
        self.assertNotIn("model", payloads[0])
        self.assertEqual(result.request_count, 2)

    def test_dimension_mismatch_is_not_silenced(self) -> None:
        client = GmsEmbeddingClient(
            api_key="fake-for-test",
            openai_poster=lambda _: {
                "data": [{"index": 0, "embedding": [0.0] * 3072}]
            },
            gemini_poster=lambda _: {},
        )
        with self.assertRaisesRegex(DimensionMismatchError, "GMS가 차원 파라미터"):
            client.embed(["질문"], MODEL_SPECS["text-embedding-3-large"])


if __name__ == "__main__":
    unittest.main()
