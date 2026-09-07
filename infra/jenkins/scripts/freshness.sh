#!/usr/bin/env bash
set -euo pipefail
expected="${FRESHNESS_EXPECTED_SHA:-${CI_COMMIT_SHA:-}}"
actual="${FRESHNESS_ACTUAL_SHA:-}"
[[ "${expected}" =~ ^[0-9a-f]{40}$ ]] || { echo 'invalid expected full SHA' >&2; exit 64; }
if [[ -z "${actual}" ]]; then
  [[ -n "${CI_BRANCH:-}" ]] || { echo 'CI_BRANCH is required' >&2; exit 64; }
  remote="${SCM_REMOTE:-origin}"
  actual="$(git ls-remote --heads "${remote}" "refs/heads/${CI_BRANCH}" | awk 'NR==1{print $1}')"
fi
[[ "${actual}" =~ ^[0-9a-f]{40}$ ]] || { echo 'could not resolve current branch head' >&2; exit 69; }
if [[ "${expected}" != "${actual}" ]]; then
  printf '{"status":"SUPERSEDED","expected":"%s","actual":"%s"}\n' "${expected}" "${actual}"
  exit 75
fi
printf '{"status":"CURRENT","commit":"%s"}\n' "${expected}"
