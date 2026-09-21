#!/usr/bin/env bash
# 이벤트 상점 운영 상품을 관리자 API로 맞춘다 — Production 에 상품이 0행이라 화면이 비어 있던
# 사고(T-167)의 재발 방지다. DB 에 직접 INSERT 하지 않는다: 도메인 검증·권한 확인·admin_actions
# 감사가 전부 API 뒤에 있고, 그게 실제 운영자가 쓰는 경로다.
#
# 재실행은 안전하다. 이름으로 대조해서 없으면 만들고, 같으면 아무것도 하지 않는다.
# 값이 다르면 기본적으로 멈춘다(65) — 운영자가 손으로 고친 값을 조용히 덮지 않기 위해서다.
# 덮어쓰려면 --apply-updates 를 명시한다.
set -euo pipefail

usage() {
  echo "usage: $0 --base-url <url> --manifest <path> --token-file <path> [--apply-updates] [--evidence <path>]" >&2
}

base_url=''
manifest=''
token_file=''
apply_updates=0
evidence=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) base_url="${2:-}"; shift 2 ;;
    --manifest) manifest="${2:-}"; shift 2 ;;
    --token-file) token_file="${2:-}"; shift 2 ;;
    --apply-updates) apply_updates=1; shift ;;
    --evidence) evidence="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 64 ;;
  esac
done
[[ -n "${base_url}" && -n "${manifest}" && -n "${token_file}" ]] || { usage; exit 64; }
[[ -r "${manifest}" ]] || { echo "manifest is not readable: ${manifest}" >&2; exit 64; }
[[ -r "${token_file}" ]] || { echo 'admin token file is not readable' >&2; exit 64; }

curl_bin="${CURL_BIN:-curl}"
admin_token="$(tr -d '\r\n' <"${token_file}")"
[[ -n "${admin_token}" ]] || { echo 'admin token file is empty' >&2; exit 64; }
prizes_url="${base_url%/}/api/v1/admin/event-shop/prizes"

# 토큰은 인자로 넘기지 않는다 — ps 목록에 남는다. 헤더는 stdin 으로 준다.
call() {
  local method="$1" url="$2" body="${3:-}"
  if [[ -n "${body}" ]]; then
    "${curl_bin}" -sS -X "${method}" "${url}" -H @- -H 'Content-Type: application/json' --data "${body}" \
      <<<"Authorization: Bearer ${admin_token}"
  else
    "${curl_bin}" -sS -X "${method}" "${url}" -H @- <<<"Authorization: Bearer ${admin_token}"
  fi
}

# 상품 이름에 공백이 있다(교보문고 10000원권) — 중간 산출물은 줄 단위 파일로 모은다.
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

call GET "${prizes_url}" >"${work}/current.json"

plan="$(python3 - "${manifest}" "${apply_updates}" "${work}/current.json" <<'PY'
import json, sys

desired = json.load(open(sys.argv[1], encoding="utf-8"))["prizes"]
apply_updates = sys.argv[2] == "1"
remote = json.load(open(sys.argv[3], encoding="utf-8"))
if isinstance(remote, dict):
    remote = remote.get("prizes", [])

index = {}
for prize in remote:
    name = prize["name"]
    if name in index:
        raise SystemExit(f"DUPLICATE\t{name}")
    index[name] = prize

FIELDS = ("priceCoin", "stock", "winnerCount", "active")
lines, differing = [], []
for want in desired:
    have = index.get(want["name"])
    body = {
        "name": want["name"],
        "priceCoin": want["priceCoin"],
        "stock": want["stock"],
        "closesAt": want.get("closesAt"),
        "winnerCount": want.get("winnerCount", 0),
        "active": want.get("active", True),
    }
    if have is None:
        # 빈 칸을 두지 않는다 — 탭은 IFS 공백이라 연속 탭이 한 칸으로 합쳐진다.
        lines.append("CREATE\t-\t" + json.dumps(body, ensure_ascii=False))
        continue
    diff = [f for f in FIELDS if have.get(f) != body[f]] + (
        ["closesAt"] if (have.get("closesAt") or None) != body["closesAt"] else []
    )
    if not diff:
        lines.append("UNCHANGED\t-\t" + want["name"])
    elif apply_updates:
        lines.append(f"UPDATE\t{have['prizeId']}\t" + json.dumps(body, ensure_ascii=False))
    else:
        differing.append(want["name"] + "(" + ",".join(diff) + ")")

if differing:
    raise SystemExit("DIFFERS\t" + ";".join(differing))
print("\n".join(lines))
PY
)" || {
  echo "operational data provisioning stopped: ${plan}" >&2
  exit 65
}

: >"${work}/created"
: >"${work}/updated"
unchanged=0
while IFS=$'\t' read -r action prize_id payload; do
  [[ -n "${action}" ]] || continue
  case "${action}" in
    CREATE)
      call POST "${prizes_url}" "${payload}" >/dev/null
      python3 -c 'import json,sys; print(json.loads(sys.argv[1])["name"])' "${payload}" >>"${work}/created"
      ;;
    UPDATE)
      call PUT "${prizes_url}/${prize_id}" "${payload}" >/dev/null
      python3 -c 'import json,sys; print(json.loads(sys.argv[1])["name"])' "${payload}" >>"${work}/updated"
      ;;
    UNCHANGED) unchanged=$((unchanged + 1)) ;;
  esac
done <<<"${plan}"

summary="$(python3 - "${base_url}" "${unchanged}" "${work}/created" "${work}/updated" <<'PY'
import json, sys
import pathlib
base_url, unchanged = sys.argv[1], int(sys.argv[2])
names = lambda path: [line for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines() if line]
print(json.dumps({
    "baseUrl": base_url,
    "created": names(sys.argv[3]),
    "updated": names(sys.argv[4]),
    "unchanged": unchanged,
}, ensure_ascii=False))
PY
)"
if [[ -n "${evidence}" ]]; then
  mkdir -p "$(dirname "${evidence}")"
  printf '%s\n' "${summary}" >"${evidence}"
fi
printf '%s\n' "${summary}"
