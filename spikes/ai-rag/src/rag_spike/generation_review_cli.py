"""답변 생성 비교 결과 JSON을 사람이 채점할 Markdown으로 변환한다."""

from __future__ import annotations

import argparse
from pathlib import Path

from .generation_review_markdown import render_file


def main() -> None:
    parser = argparse.ArgumentParser(description="Generation comparison review renderer")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--title", default="RAG")
    args = parser.parse_args()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    render_file(args.input, args.output, args.title)
    print(f"검토 문서 저장: {args.output}")


if __name__ == "__main__":
    main()
