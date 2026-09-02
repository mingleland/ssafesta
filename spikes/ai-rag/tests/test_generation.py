"""Gold 질문 로딩, context 조립, 후보 LLM 답변 생성을 검증한다."""

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from rag_spike.generation import (
    DEFAULT_PRESET,
    AgentPreset,
    build_context,
    build_system_prompt,
    generate_answer,
    load_gold_cases,
    max_tokens_for,
)
from rag_spike.models import ModelSpec, SearchHit


def hit(chunk_id: str, content: str) -> SearchHit:
    return SearchHit(
        chunk_id=chunk_id, page=1, token_count=5, content=content,
        distance=0.1, booth_id=1, agent_id=1,
    )


class LoadGoldCasesTest(unittest.TestCase):
    def test_keeps_no_answer_rows_with_gold_answer(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "gold.jsonl"
            path.write_text(
                "\n".join(
                    [
                        json.dumps(
                            {
                                "id": "q1",
                                "query": "운영 시간은?",
                                "answer_status": "ANSWERABLE",
                                "answer": "오전 10시부터 오후 6시까지입니다.",
                                "relevant_contains": ["운영 시간"],
                            },
                            ensure_ascii=False,
                        ),
                        json.dumps(
                            {
                                "id": "q2",
                                "query": "주차는 무료인가요?",
                                "answer_status": "NO_ANSWER",
                                "answer": "문서에 근거가 없습니다.",
                                "relevant_contains": [],
                            },
                            ensure_ascii=False,
                        ),
                    ]
                ),
                encoding="utf-8",
            )
            cases = load_gold_cases(path)
        self.assertEqual(len(cases), 2)
        self.assertEqual(cases[1].answer_status, "NO_ANSWER")
        self.assertEqual(cases[1].gold_answer, "문서에 근거가 없습니다.")

    def test_rejects_empty_file(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "empty.jsonl"
            path.write_text("", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "비어"):
                load_gold_cases(path)


class BuildContextTest(unittest.TestCase):
    def test_numbers_each_chunk(self) -> None:
        context = build_context([hit("a", "첫 문장"), hit("b", "둘째 문장")])
        self.assertIn("[chunk 1] 첫 문장", context)
        self.assertIn("[chunk 2] 둘째 문장", context)

    def test_empty_hits_produces_placeholder(self) -> None:
        self.assertIn("없음", build_context([]))


class AgentPresetPromptTest(unittest.TestCase):
    def test_default_preset_is_guide_friendly_medium(self) -> None:
        self.assertEqual(DEFAULT_PRESET.role, "GUIDE")
        self.assertEqual(DEFAULT_PRESET.tone, "FRIENDLY")
        self.assertEqual(DEFAULT_PRESET.response_length, "MEDIUM")

    def test_max_tokens_follows_docs_08_proposed_mapping(self) -> None:
        self.assertEqual(max_tokens_for(AgentPreset("GUIDE", "FRIENDLY", "SHORT")), 200)
        self.assertEqual(max_tokens_for(AgentPreset("GUIDE", "FRIENDLY", "MEDIUM")), 400)
        self.assertEqual(max_tokens_for(AgentPreset("GUIDE", "FRIENDLY", "LONG")), 800)

    def test_system_prompt_layers_role_tone_length_and_safety(self) -> None:
        prompt = build_system_prompt(AgentPreset("PROJECT_DOCENT", "PROFESSIONAL", "SHORT"))
        self.assertIn("전시 프로젝트", prompt)
        self.assertIn("격식체", prompt)
        self.assertIn("1~3문장", prompt)
        self.assertIn("확인할 수 없습니다", prompt)

    def test_forbidden_topics_are_included_when_present(self) -> None:
        preset = AgentPreset("GUIDE", "FRIENDLY", "MEDIUM", forbidden_topics=("개인정보",))
        prompt = build_system_prompt(preset)
        self.assertIn("개인정보", prompt)

    def test_rejects_unknown_role(self) -> None:
        with self.assertRaises(KeyError):
            build_system_prompt(AgentPreset("UNKNOWN_ROLE", "FRIENDLY", "MEDIUM"))


class GenerateAnswerTest(unittest.TestCase):
    def test_wraps_chat_result_with_case_metadata(self) -> None:
        model = ModelSpec(model_id="gpt-4.1-nano", provider="openai", credit_per_request=1.0)

        class FakeClient:
            def complete(self, *, system_prompt, user_prompt, model, temperature, max_tokens):
                self.received = (system_prompt, user_prompt, model, temperature, max_tokens)
                from rag_spike.gms_chat import ChatResult

                return ChatResult(
                    text="오전 10시부터입니다.",
                    elapsed_seconds=0.5,
                    request_count=1,
                    estimated_credits=1.0,
                    success=True,
                )

        client = FakeClient()
        result = generate_answer(
            client=client,
            model=model,
            case_id="q1",
            query="운영 시간은?",
            hits=[hit("a", "운영 시간은 오전 10시부터입니다.")],
            preset=DEFAULT_PRESET,
        )
        self.assertEqual(result.model_id, "gpt-4.1-nano")
        self.assertEqual(result.case_id, "q1")
        self.assertTrue(result.success)
        self.assertEqual(result.answer, "오전 10시부터입니다.")
        self.assertIn("운영 시간은?", client.received[1])
        self.assertIn("[chunk 1]", client.received[1])
        self.assertEqual(client.received[3], 0.0)
        self.assertEqual(client.received[4], 400)


if __name__ == "__main__":
    unittest.main()
