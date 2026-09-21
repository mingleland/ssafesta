#!/usr/bin/env bash
# 운영 데이터 주입과 마스터 관리자 승격의 계약을 고정한다.
# 실제 API·DB 는 부르지 않는다 — curl 과 docker 를 스텁으로 갈아끼운다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
provision="${repo_root}/infra/deploy/scripts/provision-operational-data.sh"
bootstrap="${repo_root}/infra/deploy/scripts/bootstrap-master-admin.sh"
manifest="${repo_root}/infra/deploy/data/operational/event-prizes.json"

fail() { echo "FAIL: $*" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
echo 'admin-token' >"${work}/token"

cat >"${work}/curl" <<'STUB'
#!/usr/bin/env bash
method=GET
url=''
data=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    -X) method="$2"; shift 2 ;;
    --data) data="$2"; shift 2 ;;
    -H) shift 2 ;;
    http*) url="$1"; shift ;;
    *) shift ;;
  esac
done
cat >/dev/null
if [[ "${method}" == GET ]]; then
  cat "${CURL_FIXTURE}"
else
  printf '%s %s %s\n' "${method}" "${url}" "${data}" >>"${CURL_LOG}"
  echo '{}'
fi
STUB
chmod +x "${work}/curl"
export CURL_BIN="${work}/curl"

run_provision() {
  CURL_FIXTURE="$1" CURL_LOG="${work}/curl.log" \
    bash "${provision}" --base-url https://example.test --manifest "${manifest}" --token-file "${work}/token" "${@:2}"
}

# 1) 상품이 하나도 없으면 manifest 5종을 전부 만든다
: >"${work}/curl.log"
echo '[]' >"${work}/empty.json"
summary="$(run_provision "${work}/empty.json")"
[[ "$(grep -c '^POST ' "${work}/curl.log")" -eq 5 ]] || fail "absent prizes must be created: $(cat "${work}/curl.log")"
grep -Fq '"unchanged": 0' <<<"${summary}" || fail "summary must report nothing unchanged: ${summary}"
grep -Fq '교보문고 10000원권' <<<"${summary}" || fail '공백이 있는 이름도 그대로 보고해야 한다'

# manifest 그대로인 원격 상태를 만든다
python3 - "${manifest}" >"${work}/same.json" <<'PY'
import json, sys
prizes = json.load(open(sys.argv[1], encoding="utf-8"))["prizes"]
print(json.dumps([
    {"prizeId": i + 1, "name": p["name"], "priceCoin": p["priceCoin"], "stock": p["stock"],
     "active": True, "closesAt": None, "winnerCount": p["winnerCount"]}
    for i, p in enumerate(prizes)
], ensure_ascii=False))
PY

# 2) 같은 상태에서 재실행하면 아무것도 쓰지 않는다
: >"${work}/curl.log"
summary="$(run_provision "${work}/same.json")"
[[ ! -s "${work}/curl.log" ]] || fail "idempotent rerun must not write: $(cat "${work}/curl.log")"
grep -Fq '"unchanged": 5' <<<"${summary}" || fail "rerun must report 5 unchanged: ${summary}"

# 3) 값이 다르면 멈춘다 — 운영자가 고친 값을 조용히 덮지 않는다
python3 - "${work}/same.json" >"${work}/differs.json" <<'PY'
import json, sys
prizes = json.load(open(sys.argv[1], encoding="utf-8"))
prizes[0]["priceCoin"] = 12345
print(json.dumps(prizes, ensure_ascii=False))
PY
: >"${work}/curl.log"
if run_provision "${work}/differs.json" >"${work}/out" 2>"${work}/err"; then
  fail 'differing prize must stop provisioning'
fi
grep -q 'DIFFERS' "${work}/err" || fail "stop must name the differing field: $(cat "${work}/err")"
[[ ! -s "${work}/curl.log" ]] || fail 'stop must happen before any write'

# 4) --apply-updates 를 명시하면 그 상품만 PUT 한다
: >"${work}/curl.log"
run_provision "${work}/differs.json" --apply-updates >/dev/null
[[ "$(grep -c '^PUT ' "${work}/curl.log")" -eq 1 ]] || fail "only the differing prize must be updated: $(cat "${work}/curl.log")"
if grep -q '^POST ' "${work}/curl.log"; then fail 'update must not create duplicates'; fi

# 5) 같은 이름이 두 번 있으면 어느 쪽을 고쳐야 할지 알 수 없다 — 멈춘다
python3 - "${work}/same.json" >"${work}/duplicate.json" <<'PY'
import json, sys
prizes = json.load(open(sys.argv[1], encoding="utf-8"))
prizes.append(dict(prizes[0], prizeId=99))
print(json.dumps(prizes, ensure_ascii=False))
PY
if run_provision "${work}/duplicate.json" >/dev/null 2>"${work}/err"; then
  fail 'duplicate prize names must stop provisioning'
fi
grep -q 'DUPLICATE' "${work}/err" || fail "duplicate stop must say so: $(cat "${work}/err")"

# ── bootstrap-master-admin ────────────────────────────────────────────────
cat >"${work}/docker" <<'STUB'
#!/usr/bin/env bash
sql="$(cat)"
case "${sql}" in
  *oauth_identities*) printf '%s\n' "${LOOKUP_RESULT}" ;;
  *BEGIN*) printf '%s\n' "${sql}" >>"${PSQL_LOG}" ;;
  *"SELECT is_master"*) printf 't\n' ;;
esac
STUB
chmod +x "${work}/docker"

run_bootstrap() {
  DOCKER_BIN="${work}/docker" POSTGRES_CONTAINER=stub PSQL_LOG="${work}/psql.log" LOOKUP_RESULT="$1" \
    bash "${bootstrap}" --env prod --provider GOOGLE --subject sub-1 "${@:2}"
}

: >"${work}/psql.log"
out="$(run_bootstrap '7|f')"
grep -q 'DRY_RUN' <<<"${out}" || fail "default run must be a dry run: ${out}"
[[ ! -s "${work}/psql.log" ]] || fail 'dry run must not write'

: >"${work}/psql.log"
out="$(run_bootstrap '7|f' --apply)"
grep -q 'BEGIN' "${work}/psql.log" || fail 'apply must run one transaction'
grep -q 'admin_actions' "${work}/psql.log" || fail 'apply must record an audit row'
grep -q '"afterIsMaster":true' <<<"${out}" || fail "apply must report the new state: ${out}"

: >"${work}/psql.log"
out="$(run_bootstrap '7|t' --apply)"
grep -q 'MASTER_ADMIN_ALREADY' <<<"${out}" || fail "already-master must be a no-op: ${out}"
[[ ! -s "${work}/psql.log" ]] || fail 'already-master must not write'

if run_bootstrap '' --apply >/dev/null 2>"${work}/err"; then
  fail 'missing user must stop'
fi
grep -q 'MASTER_ADMIN_USER_ABSENT' "${work}/err" || fail "missing user must say so: $(cat "${work}/err")"

# 권한 상승은 일상 bootstrap 에 섞이지 않는다
if grep -q 'is_master' "${repo_root}/infra/deploy/scripts/bootstrap-production-data.sh"; then
  fail 'bootstrap-production-data must not grant master admin'
fi

echo 'PASS: operational data provisioning and master admin bootstrap contracts'
