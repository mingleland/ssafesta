#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
scanner="${repo_root}/infra/jenkins/scripts/secret-scan.sh"
safe="${repo_root}/infra/tests/security/fixtures/safe"; leaked="${repo_root}/infra/tests/security/fixtures/leaked"
bash "${scanner}" --path "${safe}"
if bash "${scanner}" --path "${leaked}" >/dev/null 2>&1; then
  echo 'leaked fixture passed secret scan' >&2; exit 1
fi
if bash "${scanner}" --tracked --path "${leaked}" >/dev/null 2>&1; then
  echo 'tracked leaked fixture passed secret scan' >&2; exit 1
fi

canary='FESTA_US4_CANARY_71ab2c'
us4_safe="${repo_root}/infra/tests/security/fixtures/us4/safe"
us4_leaked="${repo_root}/infra/tests/security/fixtures/us4/leaked"
SECRET_CANARY="${canary}" bash "${scanner}" --path "${us4_safe}"
for artifact in gitlab-mr-job.log dev-batch-state.json jenkins-stage-summary.log; do
  if SECRET_CANARY="${canary}" bash "${scanner}" --path "${us4_leaked}/${artifact}" >/dev/null 2>&1; then
    echo "canary in ${artifact} was not detected" >&2; exit 1
  fi
done
untracked="$(mktemp "${safe}/.secret-scan-untracked.XXXXXX")"
trap 'rm -f "${untracked}"' EXIT
printf '%s\n' "${canary}" >"${untracked}"
SECRET_CANARY="${canary}" bash "${scanner}" --tracked --path "${safe}"
echo "PASS: secret leak fixtures cover GitLab MR and Jenkins dev-batch evidence"
