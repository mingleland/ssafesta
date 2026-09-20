#!/usr/bin/env bash
# Batch 2 Consumer-only 계약: resolve(REGISTRY_COMPLETE/BUNDLE_AVAILABLE/PUBLISH_*/WAITING/PARTIAL 65), bundle intake, source-identity gate,
# release-set 검증, image archive validator, develop.groovy 정적 계약(Jenkins 는 Unity 를 돌리지 않는다).
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; repo_root="$(cd "${script_dir}/../../.." && pwd)"
scripts="${repo_root}/infra/jenkins/scripts"
fail(){ echo "FAIL: $*" >&2; exit 1; }
work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT
mkdir -p "${work}/bin" "${work}/remote" "${work}/webgl"

# --- fixtures -------------------------------------------------------------------------------
sha='0123456789abcdef0123456789abcdef01234567'; rid="${sha:0:8}"; image_ref="festa-game:${sha}"; content_id="sha256:$(printf 'd%.0s' {1..64})"
make_zip(){ # out commit dirty
  OUT="$1" SHA="$2" DIRTY="$3" python3 - <<'PY'
import json, os, zipfile
m={'loaderUrl':'Build/a.loader.js','dataUrl':'Build/b.data.unityweb','frameworkUrl':'Build/c.framework.js.unityweb','codeUrl':'Build/d.wasm.unityweb',
   'sourceCommit':os.environ['SHA'],'sourceBranch':'develop','dirty':os.environ['DIRTY']=='true','unityVersion':'6000.0.78f1','buildProfile':'release'}
with zipfile.ZipFile(os.environ['OUT'],'w') as z:
    z.writestr('index.html','x'); z.writestr('manifest.json',json.dumps(m)); z.writestr('TemplateData/s.css','')
    for k in ('loaderUrl','dataUrl','frameworkUrl','codeUrl'): z.writestr(m[k], k)
PY
}
zip="${work}/webgl/festa-webgl-release-${rid}.zip"; make_zip "${zip}" "${sha}" false
zip_sha="$(sha256sum "${zip}" | awk '{print $1}')"; printf '%s  %s\n' "${zip_sha}" "$(basename "${zip}")" >"${zip}.sha256"
cat >"${work}/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${FAKE_DOCKER_LOG:-/dev/null}"
if [[ "${1:-} ${2:-}" == 'image load' ]]; then [[ "${3:-}" == --input && -f "${4:?}" ]]; : >"${FAKE_LOADED_MARKER:-/dev/null}"; exit 0; fi
[[ "${1:-} ${2:-}" == 'image inspect' ]] || exit 64
[[ "${FAKE_IMAGE_PRESENT:-1}" == 1 || -f "${FAKE_LOADED_MARKER:-/nonexistent}" ]] || exit 1
case "${4:-}" in
  '{{.Id}}') printf '%s\n' "${FAKE_CONTENT_ID}";;
  *source-commit*) printf '%s\n' "${FAKE_LABEL_COMMIT}";;
  *) exit 64;;
esac
SH
cat >"${work}/bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
output=''; write_out=''; url=''
while [[ $# -gt 0 ]]; do case "$1" in --output) output="${2:-}";shift 2;; --write-out) write_out="${2:-}";shift 2;; --header) shift 2;; --silent|--show-error|--location) shift;; -*) exit 64;; *) url="$1";shift;; esac; done
relative="${url#*/packages/generic/}"; target="${FAKE_REMOTE_ROOT}/${relative}"
if [[ -f "${target}" ]]; then cp "${target}" "${output}"; code=200; else : >"${output}"; code=404; fi
[[ -z "${write_out}" ]] || printf '%s' "${code}"
SH
chmod +x "${work}/bin/docker" "${work}/bin/curl"
export PATH="${work}/bin:${PATH}" FAKE_REMOTE_ROOT="${work}/remote" FAKE_CONTENT_ID="${content_id}" FAKE_LABEL_COMMIT="${sha}" FAKE_DOCKER_LOG="${work}/docker.log" FAKE_LOADED_MARKER="${work}/loaded" GITLAB_DEPLOY_TOKEN=x
resolve(){ GIT_REPO_DIR="${repo_root}" "${scripts}/resolve-game-artifacts.sh" --pipeline-commit "${sha}" "$@"; }
decision(){ python3 -c 'import json,sys; print(json.load(sys.stdin)["decision"])'; }
put_registry_webgl(){ mkdir -p "${work}/remote/festa-webgl/${rid}"; cp "${zip}.sha256" "${work}/remote/festa-webgl/${rid}/"; }
put_registry_world(){ mkdir -p "${work}/remote/festa-world/${rid}"; printf '{"sourceCommit":"%s","archiveSha256":"%s","imageContentId":"%s"}' "${sha}" "$(printf 'e%.0s' {1..64})" "${content_id}" >"${work}/remote/festa-world/${rid}/festa-world-release-${rid}.json"; }
bundle_remote="${work}/remote/unity-release-bundle/${rid}"
put_bundle_meta(){ mkdir -p "${bundle_remote}"; printf '{"schemaVersion":"1.0.0","component":"game","sourceCommit":"%s","storageMode":"local-docker","imageRef":"%s","contentId":"%s"}' "${sha}" "${image_ref}" "$1" >"${bundle_remote}/image-metadata.json"; }

# --- resolve ----------------------------------------------------------------------------------
[[ "$(resolve | decision)" == WAITING_FOR_UNITY_ARTIFACT ]] || fail 'nothing anywhere → WAITING_FOR_UNITY_ARTIFACT (no Unity build is started)'
put_bundle_meta "${content_id}"
[[ "$(resolve | decision)" == BUNDLE_AVAILABLE ]] || fail 'bundle only → BUNDLE_AVAILABLE'
put_registry_world
[[ "$(resolve | decision)" == PUBLISH_WEBGL ]] || fail 'world only + bundle → PUBLISH_WEBGL'
rm -rf "${bundle_remote}"
set +e; resolve >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail "world only, no bundle → 65 PARTIAL_REGISTRY (got ${rc})"
rm -rf "${work}/remote/festa-world"; put_registry_webgl
set +e; resolve >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail "webgl only, no bundle → 65 PARTIAL_REGISTRY (got ${rc})"
put_bundle_meta "${content_id}"
[[ "$(resolve | decision)" == PUBLISH_WORLD ]] || fail 'webgl only + bundle → PUBLISH_WORLD'
put_registry_world
[[ "$(resolve | decision)" == REGISTRY_COMPLETE ]] || fail 'both canonical → REGISTRY_COMPLETE (bundle ignored)'
resolve | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["registry"]["worldContentId"].startswith("sha256:"), d' || fail 'resolve exposes canonical world contentId'
rm -rf "${work}/remote/festa-world" "${work}/remote/festa-webgl"

# --- source-identity gate (실제 git) ----------------------------------------------------------
git init -q "${work}/origin"; mkdir -p "${work}/origin/festa-unity/ProjectSettings"
printf 'm_EditorVersion: 6000.0.78f1\nm_EditorVersionWithRevision: 6000.0.78f1 (ec8a99a872be)\n' > "${work}/origin/festa-unity/ProjectSettings/ProjectVersion.txt"
git -C "${work}/origin" add . && git -C "${work}/origin" -c user.name=t -c user.email=t@t commit -q -m base; git -C "${work}/origin" branch -q -M develop
git clone -q "${work}/origin" "${work}/clone"; echo "change" > "${work}/clone/other.txt"; git -C "${work}/clone" add . && git -C "${work}/clone" -c user.name=t -c user.email=t@t commit -q -m other-change
local_only="$(git -C "${work}/clone" rev-parse HEAD)"; on_develop="$(git -C "${work}/origin" rev-parse develop)"
gate(){ GIT_REPO_DIR="${work}/clone" "${scripts}/check-game-source-identity.sh" "$@"; }
gate --source-commit "${on_develop}" --head "${on_develop}" | grep -q '^GAME_SOURCE_OK' || fail 'gate accepts develop commit'
# local_only 는 develop 에는 없지만 festa-unity 입력이 base 와 동일하므로 unityInputId 는 통과하고 ANCESTRY_WARNING 출력
gate --source-commit "${local_only}" --head "${on_develop}" 2>&1 | grep -q 'ANCESTRY_WARNING' || fail 'gate warns when commit is not on develop'
# festa-unity 내용이 변경되면 unityInputId 불일치로 exit 65 거부
echo "unity_change" > "${work}/clone/festa-unity/new.txt"; git -C "${work}/clone" add . && git -C "${work}/clone" -c user.name=t -c user.email=t@t commit -q -m unity-diff
unity_diff_sha="$(git -C "${work}/clone" rev-parse HEAD)"
set +e; gate --source-commit "${unity_diff_sha}" --head "${on_develop}" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail 'gate rejects differing unityInputId'
set +e; gate --source-commit "$(printf '5%.0s' {1..40})" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail 'gate rejects unknown commit'
make_zip "${work}/dev.zip" "${on_develop}" false; gate --source-commit "${on_develop}" --head "${on_develop}" --webgl-zip "${work}/dev.zip" >/dev/null || fail 'gate accepts matching zip'
make_zip "${work}/dirty.zip" "${on_develop}" true; set +e; gate --source-commit "${on_develop}" --webgl-zip "${work}/dirty.zip" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -ne 0 ]] || fail 'gate rejects dirty zip'
set +e; FAKE_LABEL_COMMIT="${local_only}" gate --source-commit "${on_develop}" --image-ref "${image_ref}" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail 'gate rejects image with another source-commit'
set +e; GIT_REPO_DIR="${work}/clone" "${scripts}/check-game-source-identity.sh" --source-commit "${on_develop}" --remote nowhere >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 69 ]] || fail 'gate reports fetch failure as 69'

# --- release set ------------------------------------------------------------------------------
rs(){ "${scripts}/validate-game-release-set.sh" --source-commit "${sha}" --webgl-zip "${zip}" --webgl-sha256 "$1" --image-ref "${image_ref}" --content-id "$2"; }
rs "${zip_sha}" "${content_id}" | grep -q '^GAME_RELEASE_SET_OK' || fail 'release set accepts'
set +e; rs "$(printf '0%.0s' {1..64})" "${content_id}" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail 'release set rejects zip sha mismatch'
set +e; rs "${zip_sha}" "sha256:$(printf '1%.0s' {1..64})" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail 'release set rejects contentId mismatch'
set +e; FAKE_LABEL_COMMIT="${local_only}" rs "${zip_sha}" "${content_id}" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail 'release set rejects lineage mismatch'

# --- image archive validator (OCI layout, no docker load) --------------------------------------
SHA="${sha}" IMAGE_REF="${image_ref}" WORK="${work}" python3 - <<'PY'
import hashlib, io, json, os, pathlib, tarfile
work=pathlib.Path(os.environ['WORK']); sha=os.environ['SHA']; ref=os.environ['IMAGE_REF']
config=json.dumps({'config':{'Labels':{'org.ssafy-festa.component':'game','org.ssafy-festa.managed':'true','org.ssafy-festa.source-commit':sha}}}).encode()
cfg='sha256:'+hashlib.sha256(config).hexdigest()
idx_manifest=b'{"schemaVersion":2}'; idx='sha256:'+hashlib.sha256(idx_manifest).hexdigest()
manifest=json.dumps([{'Config':'blobs/'+cfg.replace(':','/'),'RepoTags':[ref],'Layers':[]}]).encode()
index=json.dumps({'manifests':[{'digest':idx}]}).encode()
with tarfile.open(work/'image.tar','w') as t:
    for name,body in [('manifest.json',manifest),('index.json',index),('blobs/'+cfg.replace(':','/'),config),('blobs/'+idx.replace(':','/'),idx_manifest)]:
        i=tarfile.TarInfo(name); i.size=len(body); t.addfile(i, io.BytesIO(body))
(work/'ids').write_text(cfg+'\n'+idx+'\n')
PY
{ read -r cfg_id; read -r idx_id; } <"${work}/ids"
v="${scripts}/validate-game-image-archive.sh"
"${v}" "${work}/image.tar" --image-ref "${image_ref}" --content-id "${cfg_id}" --source-commit "${sha}" | grep -q '^GAME_ARCHIVE_OK' || fail 'archive validator accepts config digest domain'
"${v}" "${work}/image.tar" --image-ref "${image_ref}" --content-id "${idx_id}" | grep -q '^GAME_ARCHIVE_OK' || fail 'archive validator accepts OCI index digest domain'
set +e; "${v}" "${work}/image.tar" --image-ref "${image_ref}" --content-id "sha256:$(printf '2%.0s' {1..64})" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -ne 0 ]] || fail 'archive validator rejects unknown contentId'
set +e; "${v}" "${work}/image.tar" --image-ref "festa-game:other" --content-id "${cfg_id}" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -ne 0 ]] || fail 'archive validator rejects wrong tag'
set +e; "${v}" "${work}/image.tar" --image-ref "${image_ref}" --content-id "${cfg_id}" --source-commit "$(printf '7%.0s' {1..40})" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -ne 0 ]] || fail 'archive validator rejects label mismatch'

# --- Unity Release Bundle intake (Registry → validate → docker load) ---------------------------
intake(){ FAKE_CONTENT_ID="${idx_id}" "${scripts}/intake-unity-release-bundle.sh" --source-commit "${sha}" --dest "${work}/intake"; }
put_bundle_meta "${idx_id}"
cp "${zip}" "${bundle_remote}/festa-webgl-release-${rid}.zip"; cp "${work}/image.tar" "${bundle_remote}/festa-game-${rid}.tar"
python3 - "${zip}" "${bundle_remote}/webgl-manifest.json" <<'PY'
import sys, zipfile
open(sys.argv[2], 'wb').write(zipfile.ZipFile(sys.argv[1]).read('manifest.json'))
PY
rm -f "${FAKE_LOADED_MARKER}"; : >"${FAKE_DOCKER_LOG}"
out="$(FAKE_IMAGE_PRESENT=0 intake)"; [[ "${out}" == "BUNDLE_OK ${sha} ${zip_sha} ${idx_id}" ]] || fail "intake: ${out}"
grep -q '^image load --input ' "${FAKE_DOCKER_LOG}" || fail 'intake must docker load the bundle image when absent'
[[ -f "${work}/intake/festa-webgl-release-${rid}.zip.sha256" ]] || fail 'intake writes the zip checksum sidecar'
: >"${FAKE_DOCKER_LOG}"; FAKE_IMAGE_PRESENT=1 intake >/dev/null || fail 'intake idempotent'; ! grep -q '^image load' "${FAKE_DOCKER_LOG}" || fail 'intake must not reload a present image'
rm -f "${bundle_remote}/webgl-manifest.json"; set +e; FAKE_IMAGE_PRESENT=1 intake >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 66 ]] || fail 'incomplete bundle → 66'
python3 - "${zip}" "${bundle_remote}/webgl-manifest.json" <<'PY'
import json, sys, zipfile
d = json.loads(zipfile.ZipFile(sys.argv[1]).read('manifest.json')); d['sourceBranch'] = 'main'; json.dump(d, open(sys.argv[2], 'w'))
PY
set +e; FAKE_IMAGE_PRESENT=1 intake >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -ne 0 ]] || fail 'bundle whose external manifest differs from the zip must be rejected'
set +e; FAKE_IMAGE_PRESENT=1 FAKE_LABEL_COMMIT="$(printf '9%.0s' {1..40})" intake >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -ne 0 ]] || fail 'loaded image with another source-commit must be rejected'
rm -rf "${bundle_remote}"

# --- develop.groovy 정적 계약 -----------------------------------------------------------------
python3 - "${repo_root}/infra/jenkins/pipelines/develop.groovy" <<'PY' || fail 'develop.groovy game flow contract'
import sys
text = open(sys.argv[1], encoding='utf-8').read()
game = text[text.index("if (!hasGame) { return }"):]
order = ["stage('Resolve Game Artifacts')", "WAITING_FOR_UNITY_ARTIFACT",
         "stage('Publish Game Artifacts')", "intake-unity-release-bundle.sh", "check-game-source-identity.sh", "publish-webgl-release.sh", "--no-trigger",
         "publish-world-release.sh", "validate-game-release-set.sh", "stage('Deploy Game Release Set')",
         "deploy-game.sh", "game-readiness.sh", "deploy-webgl-release.sh", "promote-game.sh"]
pos = [game.index(s) for s in order]
assert pos == sorted(pos), 'game stages are out of contract order'
assert "buildComponent('game')" not in game and "unity-6000.0.78f1" not in game and 'ci/build' not in game, 'Jenkins must not build Unity'
assert game.count("rollback-game.sh") >= 2, 'readiness and WebGL activation failures must both roll the world back'
assert "buildWithParameters" not in game, 'develop must deploy WebGL itself, not trigger the fallback job'
assert game.index("deploy-webgl-release.sh") > game.index("game-readiness.sh"), 'WebGL current flips only after World readiness'
assert game.index("promote-game.sh") > game.index("deploy-webgl-release.sh"), 'promote only after both artifacts are live'
assert "gitUsernamePassword(credentialsId: checkoutCredentialId)" in game, 'source gate must use the SCM checkout credential'
PY
echo 'PASS: game artifacts are resolved without rebuilding published sources, gated on SCM identity, and validated as one release set'
