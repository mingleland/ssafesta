#!/usr/bin/env bash
# 빌드 실패를 그 원인이 된 파트에게 알린다.
#
# 실패가 Jenkins 안에만 남으면 인프라 담당 한 사람만 보게 된다. 파트 CI 실패도 develop 빌드에서
# 터지는데, GitLab 은 외부 커밋 상태에 대해 알림을 보내지 않는다 (2026-09-18).
#
# 알림이 빌드를 다시 실패시키면 안 된다 — 모든 경로에서 0 으로 끝난다.
set -uo pipefail

artifact_root="${CI_ARTIFACT_DIR:-artifacts/develop}"
result="${BUILD_RESULT:-FAILURE}"

# NOT_BUILT 은 의도한 건너뜀이다 (superseded, WebGL 프리팹 불일치 SKIP). 알리면 소음이 된다.
if [[ "${result}" == NOT_BUILT ]]; then
  echo 'NOTIFY_SKIPPED: deliberate skip (NOT_BUILT)'
  exit 0
fi

# 어느 컴포넌트에서 멈췄는지는 component.groovy 가 남긴 표식으로 판정한다.
component='infra'
if [[ -r "${artifact_root}/current-component" ]]; then
  marker="$(tr -d '[:space:]' <"${artifact_root}/current-component")"
  [[ "${marker}" =~ ^(ai|back|front|game)$ ]] && component="${marker}"
fi

# NOTIFY_OWNERS 는 "back=@kim,front=@lee" 꼴이다. 파트마다 환경변수를 따로 두면 Jenkins·compose·casc
# 세 곳을 파트 수만큼 고쳐야 한다. 못 찾으면 멘션 없이 보낸다 — 알림 자체는 나가야 한다.
owner="$(printf '%s' "${NOTIFY_OWNERS:-}" | tr ',' '\n' \
  | sed -n "s/^[[:space:]]*${component}[[:space:]]*=[[:space:]]*//p" | head -1)"

commit_subject='(unknown)'
if git rev-parse HEAD >/dev/null 2>&1; then
  commit_subject="$(git log -1 --format='%h %s' 2>/dev/null || echo '(unknown)')"
fi

console_url="${BUILD_URL:-}"
[[ -z "${console_url}" ]] || console_url="${console_url}console"

# 콘솔은 최근 10빌드만 보관된다. 요약을 알림에 박아 두면 로그가 지워진 뒤에도 기록이 남는다.
text="#### :x: ${JOB_NAME:-unknown} #${BUILD_NUMBER:-?} ${result} — ${component}"
text="${text}"$'\n'"| | |"
text="${text}"$'\n'"|---|---|"
text="${text}"$'\n'"| 파트 | ${component} ${owner} |"
text="${text}"$'\n'"| 커밋 | ${commit_subject} |"
text="${text}"$'\n'"| 선택 | ${SELECTED_COMPONENTS:-(unknown)} |"
[[ -z "${console_url}" ]] || text="${text}"$'\n'"| 콘솔 | ${console_url} |"

if [[ -z "${MATTERMOST_WEBHOOK_URL:-}" ]]; then
  # 웹훅이 없어도 콘솔에는 남긴다. 설정 누락으로 알림이 조용히 사라지지 않게.
  printf 'NOTIFY_UNSENT (no webhook configured)\n%b\n' "${text}"
  exit 0
fi

payload="$(TEXT="${text}" python3 -c 'import json,os; print(json.dumps({"text": os.environ["TEXT"]}))' 2>/dev/null)"
if [[ -z "${payload}" ]]; then
  echo 'NOTIFY_UNSENT: could not build the webhook payload' >&2
  exit 0
fi

# --data @- 로 표준입력을 쓴다. 명령줄에 실으면 payload 가 프로세스 목록에 노출된다.
if printf '%s' "${payload}" | curl -fsS -X POST -H 'Content-Type: application/json' --data @- "${MATTERMOST_WEBHOOK_URL}" >/dev/null; then
  echo "NOTIFY_SENT: ${component} ${result}"
else
  # 웹훅 URL 은 절대 로그에 남기지 않는다.
  echo 'NOTIFY_FAILED: webhook POST did not succeed' >&2
fi
exit 0
