#!/usr/bin/env bash
# Jenkins 는 Unity Editor 를 돌리지 않는다 (Batch 2 Consumer-only). Registry 상태만 보고 game 구간의 다음 단계를 정한다.
#
#   REGISTRY_COMPLETE            canonical festa-webgl/<8sha> + festa-world/<8sha> 둘 다 있다 → 그대로 deploy
#   BUNDLE_AVAILABLE             canonical 이 없고 Unity Release Bundle(unity-release-bundle/<8sha>)이 있다 → validate → publish 둘 다 → deploy
#   PUBLISH_WEBGL / PUBLISH_WORLD canonical 한쪽만 있고 bundle 이 있다 → 없는 쪽만 bundle 에서 publish
#   WAITING_FOR_UNITY_ARTIFACT   canonical 도 bundle 도 없다 → 아무것도 만들지 않고 정상 종료(대기)
#   exit 65                      canonical 한쪽만 있는데 bundle 이 없다(PARTIAL_REGISTRY) → 사람이 본다
set -euo pipefail
set +x
usage() { echo 'Usage: resolve-game-artifacts.sh --pipeline-commit SHA [--candidate SHA]' >&2; exit 64; }
pipeline_commit='' candidate_param=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --pipeline-commit|--head|--source-commit) pipeline_commit="${2:-}"; shift 2 ;;
    --candidate) candidate_param="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[[ "${pipeline_commit}" =~ ^[0-9a-f]{40}$ ]] || usage
: "${GITLAB_DEPLOY_TOKEN:?GITLAB_DEPLOY_TOKEN is required}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="${GIT_REPO_DIR:-$(cd "${script_dir}/../../.." && pwd)}"
gitlab_api="${GITLAB_API_V4_URL:-https://lab.ssafy.com/api/v4}"
project_id="${GITLAB_PROJECT_ID:-1443023}"
base="${gitlab_api%/}/projects/${project_id}/packages/generic"
python_bin="${PYTHON_BIN:-python3}"

work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT HUP INT TERM
lookup() { # url out -> http code (200/404 만 정상)
  local code
  code="$(curl --silent --show-error --location --output "$2" --write-out '%{http_code}' --header "DEPLOY-TOKEN: ${GITLAB_DEPLOY_TOKEN}" "$1")"
  [[ "${code}" == 200 || "${code}" == 404 ]] || { echo "package lookup failed: HTTP ${code} for $1" >&2; exit 69; }
  echo "${code}"
}

# unityInputId 산출 함수 — Unity 입력의 동일성은 **내용**으로 본다 (S15P21A604-939).
#
# 같은 파일이 raw blob 과 LFS pointer 로 번갈아 커밋되면 git tree hash 가 달라진다. 그것 때문에
# 내용이 같은 번들이 거부되던 것을 unity-content-id.sh 가 없앤다. 그 계산이 실패하면 예전처럼
# tree hash 로 내려간다 — 새 경로가 깨져도 판정이 느슨해지지는 않는다.
compute_unity_input_id() {
  local c="$1" content_id=''
  content_id="$(GIT_REPO_DIR="${repo_root}" PYTHON_BIN="${python_bin}" bash "${script_dir}/unity-content-id.sh" "${c}" 2>/dev/null || true)"
  if [[ -z "${content_id}" ]]; then
    echo "UNITY_CONTENT_ID_UNAVAILABLE: falling back to the git tree hash for ${c}" >&2
  fi
  UNITY_CONTENT_ID="${content_id}" "${python_bin}" - "${repo_root}" "${c}" <<'INNER_PY'
import hashlib, os, subprocess, sys
repo, commit = sys.argv[1:3]
content_id = os.environ.get('UNITY_CONTENT_ID') or ''
try:
    proj = subprocess.check_output(['git', '-C', repo, 'show', f'{commit}:festa-unity/ProjectSettings/ProjectVersion.txt'], stderr=subprocess.DEVNULL).decode()
    v = [l.split(':')[1].strip() for l in proj.splitlines() if l.startswith('m_EditorVersion:')][0]
    r = [l.split('(')[1].split(')')[0] for l in proj.splitlines() if l.startswith('m_EditorVersionWithRevision:')][0]
    if content_id:
        source = f'content={content_id}'
    else:
        tree = subprocess.check_output(['git', '-C', repo, 'rev-parse', f'{commit}:festa-unity'], stderr=subprocess.DEVNULL).decode().strip()
        source = f'tree={tree}'
except Exception:
    sys.exit(1)
raw = f'{source}|unityVersion={v}|unityRevision={r}|buildProfile=release|apiEnvironment=Prod|artifactContract=manifest-1.0.0'
print(hashlib.sha256(raw.encode('utf-8')).hexdigest())
INNER_PY
}

head_input_id="$(compute_unity_input_id "${pipeline_commit}" 2>/dev/null || true)"
if [[ -z "${head_input_id}" ]]; then
  head_input_id="$(printf 'tree=%s|unityVersion=6000.0.78f1|unityRevision=ec8a99a872be|buildProfile=release|apiEnvironment=Prod|artifactContract=manifest-1.0.0' "${pipeline_commit}" | sha256sum | awk '{print $1}')"
fi
unity_source_sha="$(git -C "${repo_root}" log -1 --format=%H "${pipeline_commit}" -- festa-unity 2>/dev/null || echo "${pipeline_commit}")"

# 후보 커밋 목록 구성 (순서: 1. unity_source_sha, 2. 명시된 candidate, 3. 최근 5개 registry 패키지)
candidates=()
add_candidate() {
  local c="$1"
  [[ "${c}" =~ ^[0-9a-f]{40}$ ]] || return 0
  if git -C "${repo_root}" cat-file -e "${c}^{commit}" 2>/dev/null || [[ "${c}" == "${pipeline_commit}" ]]; then
    for item in "${candidates[@]:-}"; do [[ "${item}" == "${c}" ]] && return 0; done
    candidates+=("${c}")
  fi
}
add_candidate "${unity_source_sha}"
if [[ -n "${candidate_param}" ]]; then
  add_candidate "${candidate_param}"
fi

# 최근 registry 패키지 최대 5개에서 sourceCommit 수집
recent_versions="$("${python_bin}" - "${gitlab_api%/}/projects/${project_id}/packages" "${GITLAB_DEPLOY_TOKEN}" <<'INNER_PY'
import json, sys, urllib.request
url, token = sys.argv[1:3]
versions = []
for pkg in ('festa-world', 'unity-release-bundle'):
    try:
        req = urllib.request.Request(f"{url}?package_name={pkg}&per_page=5&order_by=created_at&sort=desc", headers={'DEPLOY-TOKEN': token})
        with urllib.request.urlopen(req) as resp:
            items = json.loads(resp.read().decode('utf-8'))
            for item in items:
                v = item.get('version')
                if v and len(v) == 8 and v not in versions:
                    versions.append(v)
    except Exception:
        pass
print(' '.join(versions[:5]))
INNER_PY
)"
for ver in ${recent_versions}; do
  meta_url="${base}/festa-world/${ver}/festa-world-release-${ver}.json"
  if [[ "$(lookup "${meta_url}" "${work}/temp_world.json")" == 200 ]]; then
    s_commit="$("${python_bin}" -c 'import json,sys; print(json.load(open(sys.argv[1])).get("sourceCommit") or "")' "${work}/temp_world.json" 2>/dev/null || true)"
    [[ -n "${s_commit}" ]] && add_candidate "${s_commit}"
  fi
  b_meta_url="${base}/unity-release-bundle/${ver}/image-metadata.json"
  if [[ "$(lookup "${b_meta_url}" "${work}/temp_bundle.json")" == 200 ]]; then
    b_commit="$("${python_bin}" -c 'import json,sys; print(json.load(open(sys.argv[1])).get("sourceCommit") or "")' "${work}/temp_bundle.json" 2>/dev/null || true)"
    [[ -n "${b_commit}" ]] && add_candidate "${b_commit}"
  fi
done

selected_source='' matched_by=''
registry_webgl_sha='' registry_world_sha='' registry_world_content_id='' bundle=false decision=''

for cand in "${candidates[@]}"; do
  cand_input_id="$(compute_unity_input_id "${cand}" 2>/dev/null || true)"
  if [[ -z "${cand_input_id}" && "${cand}" == "${pipeline_commit}" ]]; then
    cand_input_id="${head_input_id}"
  fi
  [[ "${cand_input_id}" == "${head_input_id}" ]] || continue

  release_id="${cand:0:8}"
  webgl_name="festa-webgl-release-${release_id}.zip"
  webgl_sha_url="${base}/${WEBGL_PACKAGE_NAME:-festa-webgl}/${release_id}/${webgl_name}.sha256"
  world_json_url="${base}/${WORLD_PACKAGE_NAME:-festa-world}/${release_id}/festa-world-release-${release_id}.json"
  bundle_meta_url="${base}/${UNITY_BUNDLE_PACKAGE_NAME:-unity-release-bundle}/${release_id}/image-metadata.json"

  r_webgl_sha='' r_world_sha='' r_world_cid='' b_avail=false
  if [[ "$(lookup "${webgl_sha_url}" "${work}/webgl_${release_id}.sha256")" == 200 ]]; then
    read -r r_webgl_sha sidecar_name _ <"${work}/webgl_${release_id}.sha256"
  fi
  if [[ "$(lookup "${world_json_url}" "${work}/world_${release_id}.json")" == 200 ]]; then
    read -r r_world_sha r_world_cid < <("${python_bin}" -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d.get("archiveSha256",""), d.get("imageContentId",""))' "${work}/world_${release_id}.json" 2>/dev/null || true)
  fi
  if [[ "$(lookup "${bundle_meta_url}" "${work}/bundle_${release_id}.json")" == 200 ]]; then
    b_avail=true
  fi

  if [[ -n "${r_webgl_sha}" && -n "${r_world_sha}" ]]; then
    selected_source="${cand}"
    registry_webgl_sha="${r_webgl_sha}"
    registry_world_sha="${r_world_sha}"
    registry_world_content_id="${r_world_cid}"
    decision=REGISTRY_COMPLETE
    if [[ "${cand}" == "${unity_source_sha}" ]]; then matched_by=exact; elif [[ "${cand}" == "${candidate_param}" ]]; then matched_by=candidate; else matched_by=recent; fi
    break
  elif [[ "${b_avail}" == true ]]; then
    selected_source="${cand}"
    bundle=true
    registry_webgl_sha="${r_webgl_sha}"
    registry_world_sha="${r_world_sha}"
    registry_world_content_id="${r_world_cid}"
    if [[ -n "${r_world_sha}" ]]; then decision=PUBLISH_WEBGL
    elif [[ -n "${r_webgl_sha}" ]]; then decision=PUBLISH_WORLD
    else decision=BUNDLE_AVAILABLE
    fi
    if [[ "${cand}" == "${unity_source_sha}" ]]; then matched_by=exact; elif [[ "${cand}" == "${candidate_param}" ]]; then matched_by=candidate; else matched_by=recent; fi
    break
  elif [[ -n "${r_webgl_sha}" || -n "${r_world_sha}" ]]; then
    echo "PARTIAL_REGISTRY: canonical package partially exists for ${release_id} but bundle is missing; a published source is never rebuilt" >&2
    exit 65
  fi
done

if [[ -z "${decision}" ]]; then
  decision=WAITING_FOR_UNITY_ARTIFACT
  selected_source="${unity_source_sha}"
  matched_by=none
fi

release_id="${selected_source:0:8}"
printf '{"schemaVersion":"1.0.0","decision":"%s","releaseId":"%s","pipelineCommit":"%s","artifactSourceCommit":"%s","unitySourceSha":"%s","unityInputId":"%s","matchedBy":"%s","registry":{"webglSha256":"%s","worldSha256":"%s","worldContentId":"%s"},"bundle":%s}\n' \
  "${decision}" "${release_id}" "${pipeline_commit}" "${selected_source}" "${unity_source_sha}" "${head_input_id}" "${matched_by}" "${registry_webgl_sha}" "${registry_world_sha}" "${registry_world_content_id}" "${bundle}"
