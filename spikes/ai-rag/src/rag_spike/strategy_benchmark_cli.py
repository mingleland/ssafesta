"""370 확정값(model/top_k) 고정, 청킹 전략만 변수로 비교하는 명령행 진입점이다."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .benchmark import STRATEGY_IDS, run_strategy_benchmark
from .embedding import DEFAULT_GEMINI_URL, DEFAULT_OPENAI_URL, GmsEmbeddingClient
from .models import MODEL_SPECS
from .store import MemoryVectorStore, PgVectorStore


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="RAG 청킹 전략 비교 벤치마크")
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--eval", dest="eval_path", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", default="text-embedding-3-large")
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--strategies", default=",".join(STRATEGY_IDS))
    parser.add_argument("--fixed-chunk-size", type=int, default=900)
    parser.add_argument("--fixed-overlap-ratio", type=float, default=0.20)
    parser.add_argument("--structural-chunk-size", type=int, default=900)
    parser.add_argument("--semantic-chunk-size", type=int, default=900)
    parser.add_argument("--semantic-breakpoint-threshold", type=float, default=0.3)
    parser.add_argument("--parent-chunk-size", type=int, default=1800)
    parser.add_argument("--child-chunk-size", type=int, default=300)
    parser.add_argument("--child-overlap-ratio", type=float, default=0.15)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--store", choices=("pgvector", "memory"), default="pgvector")
    parser.add_argument("--booth-id", type=int, default=1)
    parser.add_argument("--agent-id", type=int, default=1)
    parser.add_argument("--env-file", type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    if args.env_file:
        _load_env_file(args.env_file)
    if args.model not in MODEL_SPECS:
        raise SystemExit(f"지원하지 않는 모델: {args.model}")
    strategies = [item.strip() for item in args.strategies.split(",") if item.strip()]
    unknown = [strategy for strategy in strategies if strategy not in STRATEGY_IDS]
    if unknown:
        raise SystemExit(f"지원하지 않는 청킹 전략: {', '.join(unknown)}")
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
        report = run_strategy_benchmark(
            pdf_path=args.pdf,
            eval_path=args.eval_path,
            model=MODEL_SPECS[args.model],
            top_k=args.top_k,
            strategies=strategies,
            client=client,
            store=store,
            booth_id=args.booth_id,
            agent_id=args.agent_id,
            fixed_chunk_size=args.fixed_chunk_size,
            fixed_overlap_ratio=args.fixed_overlap_ratio,
            structural_chunk_size=args.structural_chunk_size,
            semantic_chunk_size=args.semantic_chunk_size,
            semantic_breakpoint_threshold=args.semantic_breakpoint_threshold,
            parent_chunk_size=args.parent_chunk_size,
            child_chunk_size=args.child_chunk_size,
            child_overlap_ratio=args.child_overlap_ratio,
        )
    finally:
        store.close()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"결과 저장: {args.output}")


def _load_env_file(path: Path) -> None:
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


if __name__ == "__main__":
    main()
