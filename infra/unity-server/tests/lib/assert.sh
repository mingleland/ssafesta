#!/usr/bin/env bash
set -euo pipefail

cleanup_files=()

register_cleanup_file() {
  cleanup_files+=("$1")
}

cleanup_registered_files() {
  local path
  for path in "${cleanup_files[@]}"; do
    [[ -f "${path}" ]] && rm -f -- "${path}"
  done
}

install_cleanup_trap() {
  trap cleanup_registered_files EXIT INT TERM
}

redact_text() {
  sed -E \
    -e 's/(Authorization:[[:space:]]*(Bearer|Basic))[[:space:]]+[^[:space:]]+/\1 [REDACTED]/Ig' \
    -e 's/(CONNECTION_TOKEN_SECRET(_FILE)?[[:space:]]*[:=])[[:space:]]*[^[:space:]]+/\1[REDACTED]/g' \
    -e 's/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/[REDACTED_TOKEN]/g'
}

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

pass() {
  printf 'PASS: %s\n' "$*"
}

assert_file() {
  [[ -f "$1" ]] || fail "missing file: $1"
}

assert_contains() {
  local file="$1"
  local pattern="$2"
  local message="$3"
  grep -Eq -- "${pattern}" "${file}" || fail "${message}"
}

assert_not_contains() {
  local file="$1"
  local pattern="$2"
  local message="$3"
  if grep -Eq -- "${pattern}" "${file}"; then
    fail "${message}"
  fi
}

skip() {
  printf 'SKIP: %s\n' "$*"
}
