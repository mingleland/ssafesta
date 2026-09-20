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

# --changed-since: 없는 base 는 축소하지 않고 전체를 본다(조용히 덜 보지 않는다).
if SECRET_CANARY="${canary}" bash "${scanner}" --changed-since 0000000000000000000000000000000000000000 --path "${us4_leaked}/gitlab-mr-job.log" >/dev/null 2>&1; then
  echo 'unknown base must fall back to scanning the requested path' >&2; exit 1
fi
# 바뀐 파일이 없으면 통과한다
SECRET_CANARY="${canary}" bash "${scanner}" --changed-since HEAD --path "${us4_leaked}" >/dev/null

echo "PASS: secret leak fixtures cover GitLab MR and Jenkins dev-batch evidence"
