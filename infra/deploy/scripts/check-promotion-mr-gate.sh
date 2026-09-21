#!/usr/bin/env bash
# develop → main promotion MR 을 merge 하기 전에 "무엇이 merge 를 허용하고 있는가" 를 확인한다 (Batch 1).
#
# 실측 (2026-09-20): 프로젝트는 only_allow_merge_if_pipeline_succeeds=true 다. GitLab 은 MR pipeline
# (merge_request_event, = mr-status) 이 있으면 그것을 head_pipeline 으로 잡고, 없으면 같은 SHA 에 게시된
# Jenkins external pipeline(jenkinsci/branch) 을 잡는다 (!1220 이 그렇게 막혔다). 계약은 다음 하나다:
#   authoritative pre-merge gate = GitLab 네이티브 mr-status
#   Jenkins jenkinsci/branch      = develop 배포 결과 상태 (merge 를 결정하지 않는다)
# head_pipeline 이 external 이면 계약 밖이므로 merge 하지 않고 멈춘다.
#
# 사용: check-promotion-mr-gate.sh <mr-iid>
# 환경: GITLAB_PROJECT_ID (기본 1443023), GITLAB_TOKEN (없으면 glab 인증 사용). 읽기 전용.
set -euo pipefail

iid="${1:-}"
[[ "${iid}" =~ ^[0-9]+$ ]] || { echo 'Usage: check-promotion-mr-gate.sh <mr-iid>' >&2; exit 64; }
project="${GITLAB_PROJECT_ID:-1443023}"
api="${GITLAB_API_V4_URL:-https://lab.ssafy.com/api/v4}"

fetch() {
  if [[ -n "${GITLAB_TOKEN:-}" ]]; then
    curl --fail --silent --show-error --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" "${api}/projects/${project}/merge_requests/${iid}"
  else
    command -v glab >/dev/null 2>&1 || { echo 'glab or GITLAB_TOKEN is required' >&2; exit 69; }
    glab api "projects/${project}/merge_requests/${iid}"
  fi
}

payload="$(mktemp)"
trap 'rm -f "${payload}"' EXIT
fetch >"${payload}"
python3 - "${payload}" <<'PY'
import json, sys
mr = json.load(open(sys.argv[1], encoding="utf-8"))
head = mr.get("head_pipeline") or {}
print("mr=!%s %s->%s state=%s detailed_merge_status=%s sha=%s" % (
    mr.get("iid"), mr.get("source_branch"), mr.get("target_branch"), mr.get("state"),
    mr.get("detailed_merge_status"), str(mr.get("sha"))[:8]))
print("head_pipeline id=%s source=%s status=%s ref=%s" % (head.get("id"), head.get("source"), head.get("status"), head.get("ref")))
problems = []
if mr.get("source_branch") != "develop" or mr.get("target_branch") != "main":
    problems.append("not a develop -> main promotion MR")
if mr.get("squash"):
    problems.append("squash is enabled; develop -> main must preserve ancestry")
if head.get("source") != "merge_request_event":
    problems.append("head pipeline is not the GitLab MR pipeline (mr-status); an external status must not decide the merge")
if head.get("status") != "success":
    problems.append("head pipeline status is %r, not success" % head.get("status"))
if mr.get("detailed_merge_status") != "mergeable":
    problems.append("detailed_merge_status=%r" % mr.get("detailed_merge_status"))
if problems:
    for problem in problems:
        print("GATE_BLOCK: " + problem)
    raise SystemExit(1)
print("GATE_OK: merge is allowed by the GitLab MR pipeline")
PY
