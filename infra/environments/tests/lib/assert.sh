#!/usr/bin/env bash
# 환경 계약 테스트가 공통 실패·정리·민감정보 제거 동작을 공유하게 한다.
set -euo pipefail

cleanup_paths=()

register_cleanup() {
  cleanup_paths+=("$1")
}

cleanup_registered() {
  local path
  for path in "${cleanup_paths[@]}"; do
    [[ -e "${path}" ]] && rm -rf -- "${path}"
  done
}

install_cleanup_trap() {
  trap cleanup_registered EXIT HUP INT TERM
}

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

pass() {
  printf 'PASS: %s\n' "$*"
}

skip() {
  printf 'SKIP: %s\n' "$*"
}

assert_file() {
  [[ -f "$1" ]] || fail "missing file: $1"
}

assert_dir() {
  [[ -d "$1" ]] || fail "missing directory: $1"
}

assert_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

resolve_python() {
  local candidate
  for candidate in "${PYTHON_BIN:-}" python3 python; do
    [[ -n "${candidate}" ]] || continue
    if command -v "${candidate}" >/dev/null 2>&1 && "${candidate}" -c 'import sys; assert sys.version_info.major == 3' >/dev/null 2>&1; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  return 1
}

assert_contains() {
  local file="$1" pattern="$2" message="$3"
  grep -Eq -- "${pattern}" "${file}" || fail "${message}"
}

assert_not_contains() {
  local file="$1" pattern="$2" message="$3"
  if grep -Eq -- "${pattern}" "${file}"; then
    fail "${message}"
  fi
}

assert_equals() {
  local expected="$1" actual="$2" message="$3"
  [[ "${expected}" == "${actual}" ]] || fail "${message}: expected=${expected}, actual=${actual}"
}

redact_stream() {
  sed -E \
    -e 's#(https?://)[^/@[:space:]]+:[^/@[:space:]]+@#\1[REDACTED]@#g' \
    -e 's/(Authorization:[[:space:]]*(Bearer|Basic))[[:space:]]+[^[:space:]]+/\1 [REDACTED]/Ig' \
    -e 's/((SECRET|TOKEN|PASSWORD|PRIVATE_KEY|PRESIGNED_URL)[A-Z0-9_]*[[:space:]]*[:=])[[:space:]]*[^[:space:]]+/\1[REDACTED]/Ig' \
    -e 's/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/[REDACTED_TOKEN]/g'
}
