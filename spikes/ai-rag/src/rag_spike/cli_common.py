"""CLI 진입점들이 공유하는 CSV 인자 파싱과 .env 파일 로딩이다."""

from __future__ import annotations

import os
from pathlib import Path


def parse_csv(value: str, converter: type) -> list:
    try:
        parsed = [converter(item.strip()) for item in value.split(",") if item.strip()]
    except ValueError as exc:
        raise SystemExit(f"잘못된 CSV 인자: {value}") from exc
    if not parsed:
        raise SystemExit("CSV 인자는 하나 이상의 값을 가져야 합니다.")
    return parsed


def load_env_file(path: Path) -> None:
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))
