"""정답이 없는 질문을 세 모델로 검색해 사람이 검토할 JSON을 만든다."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .embedding import DEFAULT_GEMINI_URL, DEFAULT_OPENAI_URL, GmsEmbeddingClient
from .manual_review import review_jsonl, run_manual_review
from .models import MODEL_SPECS
from .store import MemoryVectorStore


def main() -> None:
    parser = argparse.ArgumentParser(description="GMS embedding manual review comparison")
    parser.add_argument("--document", type=Path, required=True)
    parser.add_argument("--queries", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--models", default=",".join(MODEL_SPECS))
    parser.add_argument("--chunk-size", type=int, default=600)
    parser.add_argument("--overlap-ratio", type=float, default=0.15)
    parser.add_argument("--top-k", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--env-file", type=Path)
    args = parser.parse_args()
    if args.env_file:
        _load_env_file(args.env_file)
    model_ids = [value.strip() for value in args.models.split(",") if value.strip()]
    unknown = [model_id for model_id in model_ids if model_id not in MODEL_SPECS]
    if unknown:
        raise SystemExit(f"지원하지 않는 모델: {', '.join(unknown)}")
    client = GmsEmbeddingClient(
        api_key=os.environ.get("GMS_API_KEY", ""),
        openai_url=os.environ.get("GMS_OPENAI_EMBEDDINGS_URL", DEFAULT_OPENAI_URL),
        gemini_url=os.environ.get("GMS_GEMINI_EMBEDDINGS_URL", DEFAULT_GEMINI_URL),
        batch_size=args.batch_size,
    )
    report = run_manual_review(
        document_path=args.document,
        query_path=args.queries,
        models=[MODEL_SPECS[model_id] for model_id in model_ids],
        client=client,
        store=MemoryVectorStore(),
        chunk_size=args.chunk_size,
        overlap_ratio=args.overlap_ratio,
        top_k=args.top_k,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    review_path = args.output.with_name(f"{args.output.stem}-review.jsonl")
    review_path.write_text(review_jsonl(report), encoding="utf-8")
    print(f"수동 검토 결과 저장: {args.output}")
    print(f"라벨 검토 JSONL 저장: {review_path}")


def _load_env_file(path: Path) -> None:
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


if __name__ == "__main__":
    main()
