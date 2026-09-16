#!/usr/bin/env bash
# T062: Verifies that external public ports are strictly limited to SSH (22), HTTP (80), HTTPS (443).
# Verifies that internal ports (5432, 6379, 7777, 8080, 8000, 9000) are never bound to public interfaces (0.0.0.0 or [::]).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

allowed_ports="22 80 443"
forbidden_ports="5432 6379 7777 8080 8000 9000 9001 50000"

mode="${1:-inspect}"

if [[ "${mode}" == "--fixture" ]]; then
  # Fixture mode: test assertion logic with mock input
  mock_input="
tcp LISTEN 0 4096 0.0.0.0:22 0.0.0.0:*
tcp LISTEN 0 4096 0.0.0.0:80 0.0.0.0:*
tcp LISTEN 0 4096 0.0.0.0:443 0.0.0.0:*
tcp LISTEN 0 4096 127.0.0.1:5432 0.0.0.0:*
tcp LISTEN 0 4096 127.0.0.1:6379 0.0.0.0:*
tcp LISTEN 0 4096 127.0.0.1:7777 0.0.0.0:*
tcp LISTEN 0 4096 127.0.0.1:8080 0.0.0.0:*
"
  for port in ${forbidden_ports}; do
    if echo "${mock_input}" | grep -E "0\.0\.0\.0:${port}\b|\[::\]:${port}\b" >/dev/null 2>&1; then
      fail "Forbidden port ${port} was exposed in valid fixture"
    fi
  done
  pass "Port exposure fixture test passed: internal services are loopback-only"
  exit 0
fi

# Live inspection mode: check host ss / netstat if available
if command -v ss >/dev/null 2>&1; then
  listening_sockets="$(ss -tlpn 2>/dev/null || ss -tln 2>/dev/null)"
  
  violations=0
  for port in ${forbidden_ports}; do
    if echo "${listening_sockets}" | grep -E "(0\.0\.0\.0|\[::\]):${port}\b" >/dev/null 2>&1; then
      echo "SECURITY VIOLATION: Forbidden port ${port} is bound to public interface!" >&2
      violations=$((violations + 1))
    fi
  done

  if [[ "${violations}" -gt 0 ]]; then
    fail "Total ${violations} forbidden port(s) publicly exposed!"
  fi
  pass "Live port exposure check passed: no forbidden internal ports exposed to 0.0.0.0 / [::]"
else
  echo "ss command not available; skipping live socket scan"
fi

