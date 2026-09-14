#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
pipeline="${repo_root}/infra/jenkins/pipelines/unity-mr-validation.groovy"

fail() { echo "FAIL: $*" >&2; exit 1; }
require() { grep -Fq -- "$1" "${pipeline}" || fail "missing '$1'"; }
forbid() { ! grep -Fq -- "$1" "${pipeline}" || fail "forbidden '$1'"; }

[[ -f "${pipeline}" ]] || fail 'missing Unity MR validation pipeline'
require 'unity-6000.0.78f1'
require 'sourceSha'
require 'checkout('
require 'branches: [[name: sourceSha]]'
require 'CI_COMPONENT=game'
require 'bash ci/test'
require 'gitlabCommitStatus'

for command in 'ci/build' 'ci/package' 'docker compose' 'deploy-component.sh' 'deploy-dev-batch.sh' 'deploy-release.sh' 'promote-release.sh'; do
  forbid "${command}"
done

echo 'PASS: Unity MR validation checks MR head with ci/test only and posts GitLab status'
