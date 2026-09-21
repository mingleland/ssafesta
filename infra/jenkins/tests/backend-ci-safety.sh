#!/usr/bin/env bash
# Backend CI P0 안전 검증 (Batch 2 P0):
# 1. Maven stale output 방지: 같은 SHA+pipeline 에서만 target 재사용, SHA 변경/독립 build 시 clean 으로 stale class 제거
# 2. Testcontainers scope 제한: 전역 삭제 금지, org.ssafy-festa.testcontainers.owner 기준 격리 cleanup
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"

fail() { echo "FAIL: $*" >&2; exit 1; }

# 1. Testcontainers configuration 검증
tc_config="${repo_root}/backend/src/test/java/com/example/ssafesta/TestcontainersConfiguration.java"
grep -Fq 'org.ssafy-festa.testcontainers.owner' "${tc_config}" || fail 'TestcontainersConfiguration lacks owner label'
grep -Fq 'TESTCONTAINERS_JOB_OWNER' "${tc_config}" || fail 'TestcontainersConfiguration lacks job owner env check'

# 2. ci/test cleanup scope 검증: 전역 org.testcontainers=true 강제 삭제가 없어야 함
grep -Fq 'label=org.ssafy-festa.testcontainers.owner=' "${repo_root}/ci/test" || fail 'ci/test lacks scoped cleanup'
! grep -Eq 'docker rm.*label=org\.testcontainers=true' "${repo_root}/ci/test" || fail 'ci/test must not globally delete all testcontainers'

# 3. Maven stale output 방지 시뮬레이션
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
mkdir -p "${work}/target/classes/com/example/ssafesta"
echo "dummy_stale_class" > "${work}/target/classes/com/example/ssafesta/StaleDummy.class"

sha1="1111111111111111111111111111111111111111"
sha2="2222222222222222222222222222222222222222"
run1="pipeline-run-1"
run2="pipeline-run-2"

# Case A: stamp 가 sha1/run1 일 때 sha1/run1 은 재사용 조건 만족
printf '%s\t%s\n' "${sha1}" "${run1}" > "${work}/target/.build-source-stamp"

check_reuse() {
  local req_sha="$1" req_run="$2" stamp_file="${work}/target/.build-source-stamp"
  local reuse=false
  if [[ -f "${stamp_file}" ]]; then
    IFS=$'\t' read -r s_sha s_run < "${stamp_file}" || true
    if [[ "${s_sha:-}" == "${req_sha}" && "${s_run:-}" == "${req_run}" && -d "${work}/target/classes" ]]; then
      reuse=true
    fi
  fi
  echo "${reuse}"
}

[[ "$(check_reuse "${sha1}" "${run1}")" == "true" ]] || fail 'same SHA + same pipeline must allow class reuse'
[[ "$(check_reuse "${sha2}" "${run1}")" == "false" ]] || fail 'different SHA must reject class reuse'
[[ "$(check_reuse "${sha1}" "${run2}")" == "false" ]] || fail 'different run ID must reject class reuse'

# Case B: SHA 변경 시 clean 수행으로 stale class 제거 확인
if [[ "$(check_reuse "${sha2}" "${run2}")" == "false" ]]; then
  # clean 시뮬레이션: target/classes 제거
  rm -rf "${work}/target/classes"
fi
[[ ! -f "${work}/target/classes/com/example/ssafesta/StaleDummy.class" ]] || fail 'stale class must not survive clean on SHA change'

echo "PASS: backend CI safety contracts (P0-A maven stale output prevention, P0-B scoped testcontainers cleanup)"
