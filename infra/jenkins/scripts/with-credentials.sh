#!/usr/bin/env bash
set -euo pipefail
set +x
names=()
while [[ $# -gt 0 && "$1" != -- ]]; do
  [[ "$1" =~ ^[A-Z][A-Z0-9_]{2,63}$ ]] || { echo "invalid credential environment name" >&2; exit 64; }
  names+=("$1"); shift
done
[[ "${1:-}" == -- ]] || { echo "Usage: $0 [ENV_NAME ...] -- command [args...]" >&2; exit 64; }; shift
[[ $# -gt 0 ]] || { echo 'command is required' >&2; exit 64; }
if [[ ${#names[@]} -eq 0 && -n "${CI_BOUND_CREDENTIAL_NAMES:-}" ]]; then read -r -a names <<<"${CI_BOUND_CREDENTIAL_NAMES}"; fi
for name in "${names[@]}"; do [[ -n "${!name:-}" ]] || { echo "bound credential is missing: ${name}" >&2; exit 65; }; done
cleanup() { for name in "${names[@]}"; do unset "${name}" || true; done; }
trap cleanup EXIT HUP INT TERM
"$@"
