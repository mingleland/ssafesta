#!/usr/bin/env bash
# Unity Release Bundle publisher 의 멱등 계약과 E2E job 의 경계(fixture 는 demo 전용, canonical/Production 무오염)를 고정한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
publisher="${repo_root}/infra/jenkins/scripts/publish-unity-release-bundle.sh"
pipeline="${repo_root}/infra/jenkins/pipelines/unity-bundle-e2e.groovy"
job="${repo_root}/infra/jenkins/jobs/gitlab-unity-bundle-e2e.groovy"

fail() { echo "FAIL: $*" >&2; exit 1; }
for path in "${publisher}" "${pipeline}" "${job}"; do [[ -f "${path}" ]] || fail "missing ${path}"; done

# ── job/pipeline 계약
grep -q "pipelineJob('festa-unity-bundle-e2e')" "${job}" || fail 'E2E job name is the trigger contract'
# 등록되지 않은 Job DSL 파일은 reload 해도 job 이 생기지 않는다 — 실제로 한 번 겪었다.
grep -q 'gitlab-unity-bundle-e2e.groovy' "${repo_root}/infra/jenkins/casc/jobs.yaml" || fail 'E2E job must be registered in CasC jobs.yaml'
grep -q "booleanParam('FIXTURE_MODE'" "${job}" || fail 'fixture mode must be an explicit parameter'
grep -q "choiceParam('TARGET', \['demo'\]" "${job}" || fail 'only demo may be targeted'
grep -q "only the demo target is supported" "${pipeline}" || fail 'pipeline must refuse non-demo targets'
grep -q 'UNVERIFIED_FIXTURE' "${pipeline}" || fail 'fixture provenance must be reported, not hidden'
grep -q 'SOURCE_NOT_IN_REPOSITORY' "${pipeline}" || fail 'unknown source must fail without fixture mode'
grep -q 'festa-webgl-e2e' "${pipeline}" || fail 'E2E WebGL must go to its own package namespace'
grep -q 'publish-world-release.sh' "${pipeline}" && fail 'E2E must not create canonical world packages'
grep -E "publish-webgl-release.sh '\$\{webglZip\}'" "${pipeline}" | grep -q 'WEBGL_PACKAGE_NAME' && fail 'canonical webgl publish must not be reachable'
grep -q 'restoreDemo' "${pipeline}" || fail 'E2E must restore the previous demo current'
grep -q 'demo-current-before.json' "${pipeline}" || fail 'E2E must record what it borrowed'
if grep -qE 'deploy-production|production-promotion|approve-production|webgl/prod|festa-production' "${pipeline}"; then
  fail 'E2E pipeline must not touch any production path'
fi
grep -q 'validate-game-release-set.sh' "${pipeline}" || fail 'artifact contract checks stay on in fixture mode'
# package-write 는 Secret text 다 — usernamePassword 로 묶으면 실행 중에 죽는다(빌드 #1).
grep -q "string(credentialsId: writeCredentialId" "${pipeline}" || fail 'package write credential must bind as secret text'
grep -q 'apiEnvironment' "${pipeline}" || fail 'apiEnvironment must be asserted'

# ── publisher 멱등 계약 (curl 스텁)
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
commit='558d6624eb53e3ddcd72bef0bc8a2845e97d68b2'
release="${commit:0:8}"
bundle="${work}/bundle"
mkdir -p "${bundle}"
printf 'zip\n' >"${bundle}/festa-webgl-release-${release}.zip"
printf 'tar\n' >"${bundle}/festa-game-${release}.tar"
printf '{}\n' >"${bundle}/webgl-manifest.json"
printf '{"contentId":"sha256:%064d"}\n' 0 >"${bundle}/image-metadata.json"

cat >"${work}/curl" <<'STUB'
#!/usr/bin/env bash
url=''
out=''
upload=''
write_out=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) out="$2"; shift 2 ;;
    --upload-file) upload="$2"; shift 2 ;;
    --write-out) write_out="$2"; shift 2 ;;
    --header|--user|--data-urlencode|--cookie|--cookie-jar) shift 2 ;;
    http*) url="$1"; shift ;;
    *) shift ;;
  esac
done
if [[ -n "${upload}" ]]; then
  printf 'UPLOAD %s\n' "${url}" >>"${CURL_LOG}"
  exit 0
fi
if [[ "${url}" == *bundle.sha256 ]]; then
  if [[ -f "${EXISTING_BUNDLE_SHA:-/nonexistent}" ]]; then
    [[ -n "${out}" ]] && cat "${EXISTING_BUNDLE_SHA}" >"${out}"
    [[ -n "${write_out}" ]] && printf '200'
  else
    [[ -n "${write_out}" ]] && printf '404'
  fi
  exit 0
fi
exit 0
STUB
chmod +x "${work}/curl"

# validator 스텁 — 이 테스트는 게시 멱등성만 본다(아카이브 검증은 각 validator 테스트가 덮는다).
mkdir -p "${work}/bin"
cat >"${work}/bin/validate-webgl-archive.sh" <<'STUB'
#!/usr/bin/env bash
echo "WEBGL_ARCHIVE_OK 558d6624eb53e3ddcd72bef0bc8a2845e97d68b2 deadbeef"
STUB
cat >"${work}/bin/validate-game-image-archive.sh" <<'STUB'
#!/usr/bin/env bash
echo "GAME_IMAGE_ARCHIVE_OK 558d6624eb53e3ddcd72bef0bc8a2845e97d68b2 sha256:0"
STUB
chmod +x "${work}/bin/"*.sh
cp "${publisher}" "${work}/bin/publish-unity-release-bundle.sh"

run_publish() {
  # 확장된 낱말은 대입 접두어가 되지 않는다 — env 로 넘긴다.
  env GITLAB_PACKAGE_TOKEN=token CURL_BIN="${work}/curl" CURL_LOG="${work}/curl.log" "$@" \
    bash "${work}/bin/publish-unity-release-bundle.sh" --bundle-dir "${bundle}" --source-commit "${commit}" --fixture --no-trigger
}

: >"${work}/curl.log"
out="$(run_publish)"
grep -q 'PUBLISHED_UNITY_BUNDLE' <<<"${out}" || fail "first publish must upload: ${out}"
[[ "$(grep -c '^UPLOAD ' "${work}/curl.log")" -eq 5 ]] || fail "4 bundle files + checksum must be uploaded: $(cat "${work}/curl.log")"

( cd "${bundle}" && sha256sum "festa-webgl-release-${release}.zip" "festa-game-${release}.tar" webgl-manifest.json image-metadata.json ) >"${work}/remote.sha256"
: >"${work}/curl.log"
out="$(run_publish EXISTING_BUNDLE_SHA="${work}/remote.sha256")"
grep -q 'BUNDLE_EXISTS' <<<"${out}" || fail "same bytes must be idempotent: ${out}"
[[ ! -s "${work}/curl.log" ]] || fail 'idempotent republish must not upload again'

printf 'deadbeef  festa-webgl-release-%s.zip\n' "${release}" >"${work}/other.sha256"
: >"${work}/curl.log"
if run_publish EXISTING_BUNDLE_SHA="${work}/other.sha256" >/dev/null 2>"${work}/err"; then
  fail 'different bytes under the same version must stop'
fi
grep -q 'BUNDLE_IDENTITY_COLLISION' "${work}/err" || fail "collision must say so: $(cat "${work}/err")"
[[ ! -s "${work}/curl.log" ]] || fail 'collision must stop before uploading'

echo 'PASS: unity release bundle publisher and demo E2E boundaries'
