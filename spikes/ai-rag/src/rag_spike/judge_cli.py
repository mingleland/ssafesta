"""답변 생성 비교 결과 JSON을 LLM Judge로 자동 채점하는 명령행 진입점이다.

사람이 `rag-generate-review`가 만드는 markdown 체크박스를 직접 채우는 대신,
같은 5축 기준을 LLM Judge로 자동 채점한다 (S15P21A604-372 — Jira 티켓이
"사람 평가 또는 LLM Judge"를 동등하게 인정함).
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .gms_chat import GmsChatClient
from .judge import LLMJudge, score_report
from .models import GENERATION_LLM_SPECS


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="RAG generation LLM Judge auto-scoring (S15P21A604-372)"
    )
    parser.add_argument("--input", type=Path, required=True, help="generation 비교 report JSON")
    parser.add_argument("--output", type=Path, required=True, help="채점 결과 JSON 저장 경로")
    parser.add_argument(
        "--judge-model",
        default="gpt-4.1-mini",
        help="채점에 쓸 모델 id (GENERATION_LLM_SPECS 키)",
    )
    parser.add_argument("--env-file", type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    if args.env_file:
        _load_env_file(args.env_file)
    if args.judge_model not in GENERATION_LLM_SPECS:
        raise SystemExit(f"지원하지 않는 채점 모델: {args.judge_model}")

    api_key = os.environ.get("GMS_API_KEY", "")
    chat_client = GmsChatClient(api_key=api_key)
    judge = LLMJudge(client=chat_client, judge_model=GENERATION_LLM_SPECS[args.judge_model])

    report = json.loads(args.input.read_text(encoding="utf-8"))
    results = score_report(report, judge)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"채점 결과 저장: {args.output}")


def _load_env_file(path: Path) -> None:
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


if __name__ == "__main__":
    main()
