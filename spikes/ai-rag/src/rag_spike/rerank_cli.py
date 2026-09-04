"""Conda 환경에서 reranker 비교 스파이크를 재현하는 명령행 진입점이다."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .cli_common import load_env_file, parse_csv
from .embedding import DEFAULT_GEMINI_URL, DEFAULT_OPENAI_URL, GmsEmbeddingClient
from .models import MODEL_SPECS, RERANK_LLM_SPECS
from .rerank_benchmark import run_rerank_benchmark
from .reranker import DEFAULT_CROSS_ENCODER_MODEL, CrossEncoderReranker, GmsLlmReranker
from .store import MemoryVectorStore, PgVectorStore


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="RAG reranker comparison spike (S15P21A604-371)")
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--eval", dest="eval_path", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", default="text-embedding-3-large")
    parser.add_argument("--chunk-size", type=int, default=900)
    parser.add_argument("--overlap-ratio", type=float, default=0.2)
    parser.add_argument("--retrieval-top-k", type=int, default=10)
    parser.add_argument("--top-n", default="3,5")
    parser.add_argument("--similarity-max-distance", default="0.3,0.5")
    parser.add_argument("--llm-models", default=",".join(RERANK_LLM_SPECS))
    parser.add_argument("--cross-encoder-model", default=DEFAULT_CROSS_ENCODER_MODEL)
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
    if args.model not in MODEL_SPECS:
        raise SystemExit(f"지원하지 않는 Embedding 모델: {args.model}")
    llm_model_ids = parse_csv(args.llm_models, str)
    unknown = [model_id for model_id in llm_model_ids if model_id not in RERANK_LLM_SPECS]
    if unknown:
        raise SystemExit(f"지원하지 않는 LLM reranker 모델: {', '.join(unknown)}")

    api_key = os.environ.get("GMS_API_KEY", "")
    client = GmsEmbeddingClient(
        api_key=api_key,
        openai_url=os.environ.get("GMS_OPENAI_EMBEDDINGS_URL", DEFAULT_OPENAI_URL),
        gemini_url=os.environ.get("GMS_GEMINI_EMBEDDINGS_URL", DEFAULT_GEMINI_URL),
        batch_size=args.batch_size,
    )
    cross_encoder = CrossEncoderReranker(model_name=args.cross_encoder_model)
    gms_llm = GmsLlmReranker(api_key=api_key)

    if args.store == "pgvector":
        database_url = os.environ.get("RAG_SPIKE_DATABASE_URL", "")
        if not database_url:
            raise SystemExit("pgvector 실행에는 RAG_SPIKE_DATABASE_URL이 필요합니다.")
        store = PgVectorStore(database_url)
    else:
        store = MemoryVectorStore()
    try:
        report = run_rerank_benchmark(
            pdf_path=args.pdf,
            eval_path=args.eval_path,
            model=MODEL_SPECS[args.model],
            chunk_size=args.chunk_size,
            overlap_ratio=args.overlap_ratio,
            retrieval_top_k=args.retrieval_top_k,
            top_ns=parse_csv(args.top_n, int),
            similarity_max_distances=parse_csv(args.similarity_max_distance, float),
            llm_models=[RERANK_LLM_SPECS[model_id] for model_id in llm_model_ids],
            client=client,
            cross_encoder=cross_encoder,
            gms_llm=gms_llm,
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
