"""GMS Chat Completions 클라이언트가 OpenAI·Gemini 응답을 올바르게 파싱하고,
실패를 조용히 삼키지 않고 드러내는지 검증한다."""

import unittest

from rag_spike.gms_chat import GmsChatClient
from rag_spike.models import ModelSpec


class GmsChatClientTest(unittest.TestCase):
    def test_rejects_empty_api_key(self) -> None:
        with self.assertRaisesRegex(ValueError, "GMS_API_KEY"):
            GmsChatClient(api_key="  ")

    def test_openai_provider_extracts_message_content(self) -> None:
        model = ModelSpec(model_id="gpt-4.1-nano", provider="openai", credit_per_request=1.0)
        captured = []

        def poster_factory(spec: ModelSpec):
            def post(payload):
                captured.append(payload)
                return {"choices": [{"message": {"content": "안녕하세요"}}]}

            return post

        client = GmsChatClient(api_key="test-key", poster_factory=poster_factory)
        result = client.complete(system_prompt="sys", user_prompt="user", model=model)

        self.assertTrue(result.success)
        self.assertEqual(result.text, "안녕하세요")
        self.assertEqual(result.estimated_credits, 1.0)
        self.assertEqual(captured[0]["model"], "gpt-4.1-nano")
        self.assertEqual(
            captured[0]["messages"],
            [{"role": "system", "content": "sys"}, {"role": "user", "content": "user"}],
        )

    def test_gemini_provider_extracts_parts_text(self) -> None:
        model = ModelSpec(
            model_id="gemini-2.5-flash-lite", provider="gemini", credit_per_request=1.0
        )

        def poster_factory(spec: ModelSpec):
            def post(payload):
                return {"candidates": [{"content": {"parts": [{"text": "안녕"}]}}]}

            return post

        client = GmsChatClient(api_key="test-key", poster_factory=poster_factory)
        result = client.complete(system_prompt="sys", user_prompt="user", model=model)

        self.assertTrue(result.success)
        self.assertEqual(result.text, "안녕")

    def test_malformed_response_is_reported_as_failure_not_raised(self) -> None:
        model = ModelSpec(model_id="gpt-4.1-nano", provider="openai", credit_per_request=1.0)

        def poster_factory(spec: ModelSpec):
            def post(payload):
                return {"choices": []}

            return post

        client = GmsChatClient(api_key="test-key", poster_factory=poster_factory)
        result = client.complete(system_prompt="sys", user_prompt="user", model=model)

        self.assertFalse(result.success)
        self.assertIsNotNone(result.error)
        self.assertEqual(result.text, "")
        self.assertEqual(result.estimated_credits, 1.0)

    def test_openai_provider_sends_temperature_and_max_tokens(self) -> None:
        model = ModelSpec(model_id="gpt-4.1-nano", provider="openai", credit_per_request=1.0)
        captured = []

        def poster_factory(spec: ModelSpec):
            def post(payload):
                captured.append(payload)
                return {"choices": [{"message": {"content": "ok"}}]}

            return post

        client = GmsChatClient(api_key="test-key", poster_factory=poster_factory)
        client.complete(
            system_prompt="sys", user_prompt="user", model=model,
            temperature=0.0, max_tokens=400,
        )

        self.assertEqual(captured[0]["temperature"], 0.0)
        self.assertEqual(captured[0]["max_completion_tokens"], 400)

    def test_gemini_provider_sends_generation_config(self) -> None:
        model = ModelSpec(
            model_id="gemini-2.5-flash-lite", provider="gemini", credit_per_request=1.0
        )
        captured = []

        def poster_factory(spec: ModelSpec):
            def post(payload):
                captured.append(payload)
                return {"candidates": [{"content": {"parts": [{"text": "ok"}]}}]}

            return post

        client = GmsChatClient(api_key="test-key", poster_factory=poster_factory)
        client.complete(
            system_prompt="sys", user_prompt="user", model=model,
            temperature=0.0, max_tokens=400,
        )

        self.assertEqual(captured[0]["generationConfig"]["temperature"], 0.0)
        self.assertEqual(captured[0]["generationConfig"]["maxOutputTokens"], 400)

    def test_defaults_to_temperature_zero_when_unspecified(self) -> None:
        model = ModelSpec(model_id="gpt-4.1-nano", provider="openai", credit_per_request=1.0)
        captured = []

        def poster_factory(spec: ModelSpec):
            def post(payload):
                captured.append(payload)
                return {"choices": [{"message": {"content": "ok"}}]}

            return post

        client = GmsChatClient(api_key="test-key", poster_factory=poster_factory)
        client.complete(system_prompt="sys", user_prompt="user", model=model)

        self.assertEqual(captured[0]["temperature"], 0.0)
        self.assertNotIn("max_completion_tokens", captured[0])

    def test_unsupported_provider_is_reported_as_failure(self) -> None:
        model = ModelSpec(model_id="mystery", provider="mystery", credit_per_request=1.0)
        client = GmsChatClient(api_key="test-key", poster_factory=lambda spec: (lambda p: {}))
        result = client.complete(system_prompt="sys", user_prompt="user", model=model)
        self.assertFalse(result.success)


if __name__ == "__main__":
    unittest.main()
