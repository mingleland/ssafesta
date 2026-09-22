#!/usr/bin/env python3
"""운영 신호 카탈로그의 필수 필드와 개인정보 경계를 검증한다."""

import json
import pathlib
import sys
from collections import Counter


ALLOWED = {
    "category": {"operational", "product", "development"},
    "priority": {"P0", "P1", "P2"},
    "status": {"implemented", "ready_on_demand", "available", "missing"},
    "storage": {"prometheus", "loki", "postgresql", "filesystem", "external", "none"},
    "pii": {"none", "aggregate", "pseudonymous", "personal"},
    "aggregation": {"bounded_labels", "aggregate_only", "restricted_raw", "log_field_only"},
}
REQUIRED = {
    "id", "category", "name", "priority", "status", "source", "storage", "pii",
    "aggregation", "collection", "retention", "labels", "consumers", "evidence",
}


def normalize_label(value: object) -> str:
    return "".join(character for character in str(value).lower() if character.isalnum())


def validate(path: pathlib.Path) -> list[str]:
    try:
        catalog = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return [f"catalog cannot be read: {exc}"]

    errors: list[str] = []
    if catalog.get("schemaVersion") != "1.0.0":
        errors.append("schemaVersion must be 1.0.0")
    prohibited = {normalize_label(label) for label in catalog.get("policy", {}).get("prohibitedLabels", [])}
    if not prohibited:
        errors.append("policy.prohibitedLabels must not be empty")

    repo_root = path.parents[2]
    seen: set[str] = set()
    for index, signal in enumerate(catalog.get("signals", [])):
        prefix = signal.get("id") or f"signals[{index}]"
        missing = REQUIRED - signal.keys()
        if missing:
            errors.append(f"{prefix}: missing fields {sorted(missing)}")
            continue
        if prefix in seen:
            errors.append(f"{prefix}: duplicate id")
        seen.add(prefix)
        for field, allowed in ALLOWED.items():
            if signal[field] not in allowed:
                errors.append(f"{prefix}: invalid {field}={signal[field]}")
        if not signal["consumers"]:
            errors.append(f"{prefix}: at least one consumer is required")

        labels = {normalize_label(label) for label in signal["labels"]}
        leaked = sorted(labels & prohibited)
        if leaked:
            errors.append(f"{prefix}: prohibited labels {leaked}")
        if signal["storage"] in {"prometheus", "loki"} and signal["pii"] == "personal":
            errors.append(f"{prefix}: personal data cannot be stored in {signal['storage']}")
        if signal["pii"] == "personal" and not (
            signal["storage"] == "postgresql" and signal["aggregation"] == "restricted_raw"
        ):
            errors.append(f"{prefix}: personal data must remain restricted_raw in postgresql")
        if signal["pii"] == "pseudonymous" and signal["labels"]:
            errors.append(f"{prefix}: pseudonymous values may be log fields, never labels")
        if signal["status"] in {"implemented", "ready_on_demand"} and not signal["evidence"]:
            errors.append(f"{prefix}: {signal['status']} requires repository evidence")
        for evidence in signal["evidence"]:
            if not repo_root.joinpath(evidence).exists():
                errors.append(f"{prefix}: evidence path does not exist: {evidence}")

    if not seen:
        errors.append("signals must not be empty")
    return errors


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {pathlib.Path(sys.argv[0]).name} <signal-catalog.json>", file=sys.stderr)
        return 64
    errors = validate(pathlib.Path(sys.argv[1]).resolve())
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    signals = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["signals"]
    counts = Counter(signal["status"] for signal in signals)
    summary = " ".join(f"{status}={counts[status]}" for status in sorted(ALLOWED["status"]))
    print(f"SIGNAL_CATALOG_OK count={len(signals)} {summary}")
    urgent = sorted(signal["id"] for signal in signals
                    if signal["priority"] == "P0" and signal["status"] == "missing")
    if urgent:
        print(f"P0_MISSING {','.join(urgent)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
