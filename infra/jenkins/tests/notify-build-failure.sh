#!/usr/bin/env bash
# 실패 알림이 파트를 정확히 지목하고, 웹훅이 없거나 POST 가 실패해도 빌드를 죽이지 않는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
notifier="${repo_root}/infra/jenkins/scripts/notify-build-failure.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

mkdir -p "${work}/artifacts/develop" "${work}/bin"
cat >"${work}/bin/curl" <<'SH'
#!/usr/bin/env bash
# 표준입력 payload 와 인자를 기록하는 stub. URL 이 인자로 오는지도 함께 남긴다.
cat >"${CURL_BODY}"
printf '%s\n' "$*" >"${CURL_ARGS}"
exit "${CURL_EXIT:-0}"
SH
chmod +x "${work}/bin/curl"

run() {
  ( cd "${work}" && PATH="${work}/bin:${PATH}" CI_ARTIFACT_DIR=artifacts/develop \
      CURL_BODY="${work}/body.json" CURL_ARGS="${work}/args.txt" \
      JOB_NAME=festa-gitlab-develop/develop BUILD_NUMBER=470 BUILD_URL=https://ci.example/job/470/ \
      SELECTED_COMPONENTS='back, front, game' env "$@" bash "${notifier}" )
}

# 1) 표식이 가리키는 파트를 지목하고 담당자를 멘션한다.
printf '%s' back >"${work}/artifacts/develop/current-component"
out="$(run BUILD_RESULT=FAILURE MATTERMOST_WEBHOOK_URL=https://hook.example/xyz NOTIFY_OWNERS='ai=@ai-dev,back=@back-dev,front=@front-dev')"
grep -Fq 'NOTIFY_SENT: back FAILURE' <<<"${out}" || { echo 'did not report a sent notification' >&2; exit 1; }
grep -Fq 'back' "${work}/body.json" || { echo 'payload lost the component' >&2; exit 1; }
grep -Fq '@back-dev' "${work}/body.json" || { echo 'payload lost the owner mention' >&2; exit 1; }
! grep -Fq '@front-dev' "${work}/body.json" || { echo 'payload mentioned an unrelated part owner' >&2; exit 1; }
grep -Fq 'https://ci.example/job/470/console' "${work}/body.json" || { echo 'payload lost the console link' >&2; exit 1; }
# 웹훅 URL 은 표준입력이 아니라 인자로만 가야 한다 — payload 에 섞이면 로그·아티팩트로 샌다.
grep -Fq 'hook.example' "${work}/args.txt" || { echo 'webhook URL was not passed as an argument' >&2; exit 1; }
! grep -Fq 'hook.example' "${work}/body.json" || { echo 'webhook URL leaked into the payload' >&2; exit 1; }

# 2) 의도한 건너뜀은 알리지 않는다.
rm -f "${work}/body.json"
out="$(run BUILD_RESULT=NOT_BUILT MATTERMOST_WEBHOOK_URL=https://hook.example/xyz)"
grep -Fq 'NOTIFY_SKIPPED' <<<"${out}" || { echo 'a deliberate skip was announced' >&2; exit 1; }
[[ ! -f "${work}/body.json" ]] || { echo 'a deliberate skip posted to the webhook' >&2; exit 1; }

# 3) 웹훅 미설정이면 콘솔에 남기고 0 으로 끝난다. 설정 누락으로 조용히 사라지지 않게.
out="$(run BUILD_RESULT=FAILURE)"
grep -Fq 'NOTIFY_UNSENT' <<<"${out}" || { echo 'missing webhook was not reported' >&2; exit 1; }
grep -Fq 'back' <<<"${out}" || { echo 'console fallback lost the component' >&2; exit 1; }

# 4) POST 가 실패해도 빌드를 죽이지 않는다.
if ! run BUILD_RESULT=FAILURE MATTERMOST_WEBHOOK_URL=https://hook.example/xyz CURL_EXIT=22 >/dev/null 2>&1; then
  echo 'a failed webhook POST failed the build' >&2
  exit 1
fi

# 5) 표식이 없으면 infra 로 떨어진다 — 컴포넌트 빌드 전에 죽은 경우다.
rm -f "${work}/artifacts/develop/current-component"
out="$(run BUILD_RESULT=ABORTED MATTERMOST_WEBHOOK_URL=https://hook.example/xyz)"
grep -Fq 'NOTIFY_SENT: infra ABORTED' <<<"${out}" || { echo 'missing marker did not fall back to infra' >&2; exit 1; }

echo 'PASS: build failure notification names the responsible part and never fails the build'
