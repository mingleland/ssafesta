#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_ID:?}" "${SCM_PROVIDER:?}" "${SCM_REPOSITORY:?}" "${SCM_BRANCH:?}" "${CI_COMMIT_SHA:?}"
: "${JENKINS_JOB:?}" "${JENKINS_BUILD_NUMBER:?}" "${COMPONENT_METADATA_DIR:?}" "${RELEASE_MANIFEST_PATH:?}"
[[ "${CI_COMMIT_SHA}" =~ ^[0-9a-f]{40}$ ]] || { echo 'full lowercase commit SHA required' >&2; exit 64; }
[[ "${SCM_PROVIDER}" =~ ^(github|gitlab)$ ]] || { echo 'SCM_PROVIDER must be github or gitlab' >&2; exit 64; }
python_bin="$(command -v python3 || command -v python || true)"
[[ -n "${python_bin}" ]] || { echo 'Python 3 is required' >&2; exit 69; }

components_csv="${DEPLOY_COMPONENTS:-ai,back,front,game}"
IFS=',' read -r -a components <<<"${components_csv}"
(( ${#components[@]} > 0 )) || { echo 'DEPLOY_COMPONENTS must not be empty' >&2; exit 64; }
declare -A seen=()
for component in "${components[@]}"; do
  [[ "${component}" =~ ^(ai|back|front|game)$ ]] || { echo "invalid deploy component: ${component}" >&2; exit 64; }
  [[ -z "${seen[${component}]:-}" ]] || { echo "duplicate deploy component: ${component}" >&2; exit 64; }
  seen["${component}"]=1
done

native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
metadata_paths=()
for component in "${components[@]}"; do
  path="${COMPONENT_METADATA_DIR}/${component}.json"
  [[ -f "${path}" ]] || { echo "missing component metadata: ${component}" >&2; exit 66; }
  metadata_paths+=("$(native "${path}")")
done
output_native="$(native "${RELEASE_MANIFEST_PATH}")"
components_json="$(IFS=,; printf '%s' "${components[*]}")"

DEPLOY_COMPONENTS="${components_json}" "${python_bin}" - "${output_native}" "${metadata_paths[@]}" <<'PY'
import datetime,json,os,pathlib,sys
components=[]
expected_components=os.environ['DEPLOY_COMPONENTS'].split(',')
for expected,path in zip(expected_components,sys.argv[2:]):
    item=json.loads(pathlib.Path(path).read_text(encoding='utf-8'))
    required={'component','sourceCommit','storageMode','imageRef','contentId'}
    missing=required-set(item)
    if missing or item['component'] != expected:
        raise SystemExit(f'invalid metadata for {expected}: missing={sorted(missing)} actual={item.get("component")}')
    components.append({'name':expected,'sourceCommit':item['sourceCommit'],'storageMode':item['storageMode'],'imageRef':item['imageRef'],'contentId':item['contentId'],**({'webglArtifactUri':item['webglArtifactUri']} if item.get('webglArtifactUri') else {})})
document={
 'schemaVersion':'1.0.0','releaseId':os.environ['RELEASE_ID'],
 'scm':{'provider':os.environ['SCM_PROVIDER'],'repository':os.environ['SCM_REPOSITORY'],'branch':os.environ['SCM_BRANCH'],'commit':os.environ['CI_COMMIT_SHA']},
 'jenkins':{'job':os.environ['JENKINS_JOB'],'buildNumber':int(os.environ['JENKINS_BUILD_NUMBER']),**({'buildUrl':os.environ['JENKINS_BUILD_URL']} if os.environ.get('JENKINS_BUILD_URL') else {})},
 'components':components,
 'rollbackSafety':{'classification':os.environ.get('ROLLBACK_CLASSIFICATION','UNASSESSED'),'dataChange':os.environ.get('DATA_CHANGE','none'),'dbSchemaChanged':os.environ.get('DB_SCHEMA_CHANGED','false').lower()=='true','secretOrConfigChanged':os.environ.get('SECRET_OR_CONFIG_CHANGED','false').lower()=='true',**({'evidenceRef':os.environ['ROLLBACK_EVIDENCE_REF']} if os.environ.get('ROLLBACK_EVIDENCE_REF') else {})},
 'createdAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')}
out=pathlib.Path(sys.argv[1]); out.parent.mkdir(parents=True,exist_ok=True)
tmp=out.with_suffix(out.suffix+'.tmp'); tmp.write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8'); tmp.replace(out)
PY

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "${script_dir}/../../jenkins/scripts/validate-contracts.sh" release "${RELEASE_MANIFEST_PATH}"
echo "${RELEASE_MANIFEST_PATH}"
