#!/usr/bin/env bash
# Batch 2 consumer 계약: resolve 5분기, source-identity gate, release-set 검증, image archive validator.
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
[[ "${1:-} ${2:-}" == 'image inspect' ]] || exit 64
[[ "${FAKE_IMAGE_PRESENT:-1}" == 1 ]] || exit 1
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
export PATH="${work}/bin:${PATH}" FAKE_REMOTE_ROOT="${work}/remote" FAKE_CONTENT_ID="${content_id}" FAKE_LABEL_COMMIT="${sha}" GITLAB_DEPLOY_TOKEN=x
resolve(){ "${scripts}/resolve-game-artifacts.sh" --source-commit "${sha}" --webgl-dir "$1"; }
decision(){ python3 -c 'import json,sys; print(json.load(sys.stdin)["decision"])'; }
put_registry_webgl(){ mkdir -p "${work}/remote/festa-webgl/${rid}"; cp "${zip}.sha256" "${work}/remote/festa-webgl/${rid}/"; }
put_registry_world(){ mkdir -p "${work}/remote/festa-world/${rid}"; printf '{"sourceCommit":"%s","archiveSha256":"%s"}' "${sha}" "$(printf 'e%.0s' {1..64})" >"${work}/remote/festa-world/${rid}/festa-world-release-${rid}.json"; }

# --- resolve 5분기 ----------------------------------------------------------------------------
[[ "$(FAKE_IMAGE_PRESENT=0 resolve "${work}/empty" | decision)" == BUILD_REQUIRED ]] || fail 'none/none → BUILD_REQUIRED'
[[ "$(FAKE_IMAGE_PRESENT=0 resolve "${work}/webgl" | decision)" == BUILD_REQUIRED ]] || fail 'none + zip only → BUILD_REQUIRED (partial local is not reused)'
[[ "$(resolve "${work}/webgl" | decision)" == PUBLISH_BOTH ]] || fail 'none + local both → PUBLISH_BOTH'
put_registry_world
[[ "$(resolve "${work}/webgl" | decision)" == PUBLISH_WEBGL ]] || fail 'world only + local zip → PUBLISH_WEBGL'
set +e; resolve "${work}/empty" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail "world only, no zip → 65 (got ${rc})"
rm -rf "${work}/remote/festa-world"; put_registry_webgl
[[ "$(resolve "${work}/empty" | decision)" == PUBLISH_WORLD ]] || fail 'webgl only + local image → PUBLISH_WORLD'
set +e; FAKE_IMAGE_PRESENT=0 resolve "${work}/empty" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail "webgl only, no image → 65 (got ${rc})"
put_registry_world
[[ "$(FAKE_IMAGE_PRESENT=0 resolve "${work}/empty" | decision)" == SKIP_TO_DEPLOY ]] || fail 'both in registry → SKIP_TO_DEPLOY'

# --- source-identity gate (실제 git) ----------------------------------------------------------
git init -q "${work}/origin"; git -C "${work}/origin" -c user.name=t -c user.email=t@t commit -q --allow-empty -m base; git -C "${work}/origin" branch -q -M develop
git clone -q "${work}/origin" "${work}/clone"; git -C "${work}/clone" -c user.name=t -c user.email=t@t commit -q --allow-empty -m local-only
local_only="$(git -C "${work}/clone" rev-parse HEAD)"; on_develop="$(git -C "${work}/origin" rev-parse develop)"
gate(){ GIT_REPO_DIR="${work}/clone" "${scripts}/check-game-source-identity.sh" "$@"; }
gate --source-commit "${on_develop}" | grep -q '^GAME_SOURCE_OK' || fail 'gate accepts develop commit'
set +e; gate --source-commit "${local_only}" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail 'gate rejects commit not on origin/develop'
set +e; gate --source-commit "$(printf '5%.0s' {1..40})" >/dev/null 2>&1; rc=$?; set -e; [[ "${rc}" -eq 65 ]] || fail 'gate rejects unknown commit'
make_zip "${work}/dev.zip" "${on_develop}" false; gate --source-commit "${on_develop}" --webgl-zip "${work}/dev.zip" >/dev/null || fail 'gate accepts matching zip'
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

# --- develop.groovy 정적 계약 -----------------------------------------------------------------
python3 - "${repo_root}/infra/jenkins/pipelines/develop.groovy" <<'PY' || fail 'develop.groovy game flow contract'
import sys
text = open(sys.argv[1], encoding='utf-8').read()
game = text[text.index("if (!hasGame) { return }"):]
order = ["stage('Resolve Game Artifacts')", "if (decision == 'BUILD_REQUIRED')", "buildComponent('game')",
         "stage('Publish Game Artifacts')", "check-game-source-identity.sh", "publish-webgl-release.sh", "--no-trigger",
         "publish-world-release.sh", "validate-game-release-set.sh", "stage('Deploy Game Release Set')",
         "deploy-game.sh", "game-readiness.sh", "deploy-webgl-release.sh", "promote-game.sh"]
pos = [game.index(s) for s in order]
assert pos == sorted(pos), 'game stages are out of contract order'
assert game.count("rollback-game.sh") >= 2, 'readiness and WebGL activation failures must both roll the world back'
assert "buildWithParameters" not in game, 'develop must deploy WebGL itself, not trigger the fallback job'
assert game.index("deploy-webgl-release.sh") > game.index("game-readiness.sh"), 'WebGL current flips only after World readiness'
assert game.index("promote-game.sh") > game.index("deploy-webgl-release.sh"), 'promote only after both artifacts are live'
assert "gitUsernamePassword(credentialsId: checkoutCredentialId)" in game, 'source gate must use the SCM checkout credential'
PY
echo 'PASS: game artifacts are resolved without rebuilding published sources, gated on SCM identity, and validated as one release set'
