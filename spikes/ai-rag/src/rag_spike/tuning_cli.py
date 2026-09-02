"""문서별 튜닝 JSON을 합쳐 의사결정용 JSON과 Markdown을 만든다."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .tuning import aggregate_reports, load_reports, render_markdown


def main() -> None:
    parser = argparse.ArgumentParser(description="RAG tuning result aggregator")
    parser.add_argument("--inputs", type=Path, nargs="+", required=True)
    parser.add_argument("--json-output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    args = parser.parse_args()

    summary = aggregate_reports(load_reports(args.inputs))
    args.json_output.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
    args.json_output.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    args.markdown_output.write_text(render_markdown(summary), encoding="utf-8")
    print(f"JSON 결과 저장: {args.json_output}")
    print(f"검토 문서 저장: {args.markdown_output}")


if __name__ == "__main__":
    main()
