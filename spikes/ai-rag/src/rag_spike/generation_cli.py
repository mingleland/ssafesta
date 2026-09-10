"""Conda 환경에서 답변 생성 LLM 비교 스파이크를 재현하는 명령행 진입점이다."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .embedding import DEFAULT_GEMINI_URL, DEFAULT_OPENAI_URL, GmsEmbeddingClient
from .generation import DEFAULT_PRESET, AgentPreset
from .generation_benchmark import run_generation_benchmark
from .gms_chat import GmsChatClient
from .models import GENERATION_LLM_SPECS, MODEL_SPECS
from .store import MemoryVectorStore, PgVectorStore


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="RAG answer generation LLM comparison spike (S15P21A604-372)"
    )
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--gold", dest="gold_path", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--embedding-model", default="text-embedding-3-large")
    parser.add_argument("--chunk-size", type=int, default=900)
    parser.add_argument("--overlap-ratio", type=float, default=0.2)
    parser.add_argument("--top-n", type=int, default=5)
    parser.add_argument("--models", default=",".join(GENERATION_LLM_SPECS))
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--store", choices=("pgvector", "memory"), default="pgvector")
    parser.add_argument("--booth-id", type=int, default=1)
    parser.add_argument("--agent-id", type=int, default=1)
    parser.add_argument(
        "--response-length",
        choices=("SHORT", "MEDIUM", "LONG"),
        default=DEFAULT_PRESET.response_length,
    )
    parser.add_argument("--env-file", type=Path)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    if args.env_file:
        _load_env_file(args.env_file)
    if args.embedding_model not in MODEL_SPECS:
        raise SystemExit(f"지원하지 않는 Embedding 모델: {args.embedding_model}")
    model_ids = _csv(args.models, str)
    unknown = [model_id for model_id in model_ids if model_id not in GENERATION_LLM_SPECS]
    if unknown:
        raise SystemExit(f"지원하지 않는 생성 LLM: {', '.join(unknown)}")

    api_key = os.environ.get("GMS_API_KEY", "")
    embedding_client = GmsEmbeddingClient(
        api_key=api_key,
        openai_url=os.environ.get("GMS_OPENAI_EMBEDDINGS_URL", DEFAULT_OPENAI_URL),
        gemini_url=os.environ.get("GMS_GEMINI_EMBEDDINGS_URL", DEFAULT_GEMINI_URL),
        batch_size=args.batch_size,
    )
    chat_client = GmsChatClient(api_key=api_key)

    if args.store == "pgvector":
        database_url = os.environ.get("RAG_SPIKE_DATABASE_URL", "")
        if not database_url:
            raise SystemExit("pgvector 실행에는 RAG_SPIKE_DATABASE_URL이 필요합니다.")
        store = PgVectorStore(database_url)
    else:
        store = MemoryVectorStore()
    try:
        report = run_generation_benchmark(
            pdf_path=args.pdf,
            gold_path=args.gold_path,
            embedding_model=MODEL_SPECS[args.embedding_model],
            chunk_size=args.chunk_size,
            overlap_ratio=args.overlap_ratio,
            top_n=args.top_n,
            generation_models=[GENERATION_LLM_SPECS[model_id] for model_id in model_ids],
            embedding_client=embedding_client,
            chat_client=chat_client,
            store=store,
            booth_id=args.booth_id,
            agent_id=args.agent_id,
            preset=AgentPreset(
                role=DEFAULT_PRESET.role,
                tone=DEFAULT_PRESET.tone,
                response_length=args.response_length,
            ),
        )
    finally:
        store.close()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"결과 저장: {args.output}")


def _csv(value: str, converter: type) -> list:
    try:
        parsed = [converter(item.strip()) for item in value.split(",") if item.strip()]
    except ValueError as exc:
        raise SystemExit(f"잘못된 CSV 인자: {value}") from exc
    if not parsed:
        raise SystemExit("CSV 인자는 하나 이상의 값을 가져야 합니다.")
    return parsed


def _load_env_file(path: Path) -> None:
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


if __name__ == "__main__":
    main()
