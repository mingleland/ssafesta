"""Conda 환경에서 RAG 스파이크를 재현하는 명령행 진입점이다."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .benchmark import run_benchmark
from .cli_common import load_env_file, parse_csv
from .embedding import DEFAULT_GEMINI_URL, DEFAULT_OPENAI_URL, GmsEmbeddingClient
from .models import MODEL_SPECS
from .store import MemoryVectorStore, PgVectorStore


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="RAG retrieval benchmark")
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--eval", dest="eval_path", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--models", default=",".join(MODEL_SPECS))
    parser.add_argument("--chunk-sizes", default="300,600,900")
    parser.add_argument("--overlap-ratios", default="0.10,0.15,0.20")
    parser.add_argument("--top-k", default="3,5,8,10")
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--store", choices=("pgvector", "memory"), default="pgvector")
    parser.add_argument("--booth-id", type=int, default=1)
    parser.add_argument("--agent-id", type=int, default=1)
    parser.add_argument("--env-file", type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    if args.env_file:
        load_env_file(args.env_file)
    model_ids = parse_csv(args.models, str)
    unknown = [model_id for model_id in model_ids if model_id not in MODEL_SPECS]
    if unknown:
        raise SystemExit(f"지원하지 않는 모델: {', '.join(unknown)}")
    client = GmsEmbeddingClient(
        api_key=os.environ.get("GMS_API_KEY", ""),
        openai_url=os.environ.get("GMS_OPENAI_EMBEDDINGS_URL", DEFAULT_OPENAI_URL),
        gemini_url=os.environ.get("GMS_GEMINI_EMBEDDINGS_URL", DEFAULT_GEMINI_URL),
        batch_size=args.batch_size,
    )
    if args.store == "pgvector":
        database_url = os.environ.get("RAG_SPIKE_DATABASE_URL", "")
        if not database_url:
            raise SystemExit("pgvector 실행에는 RAG_SPIKE_DATABASE_URL이 필요합니다.")
        store = PgVectorStore(database_url)
    else:
        store = MemoryVectorStore()
    try:
        report = run_benchmark(
            pdf_path=args.pdf,
            eval_path=args.eval_path,
            models=[MODEL_SPECS[model_id] for model_id in model_ids],
            chunk_sizes=parse_csv(args.chunk_sizes, int),
            overlap_ratios=parse_csv(args.overlap_ratios, float),
            top_ks=parse_csv(args.top_k, int),
            client=client,
            store=store,
            booth_id=args.booth_id,
            agent_id=args.agent_id,
        )
    finally:
        store.close()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"결과 저장: {args.output}")


if __name__ == "__main__":
    main()
