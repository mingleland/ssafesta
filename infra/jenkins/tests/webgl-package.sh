#!/usr/bin/env bash
# WebGL 패키징 계약 (Batch 2): 결정적 zip, source-stable provenance 만 zip 안에, manifest 불일치·dirty 거부, archive validator.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; repo_root="$(cd "${script_dir}/../../.." && pwd)"
fail(){ echo "FAIL: $*" >&2; exit 1; }
work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT
sha='0123456789abcdef0123456789abcdef01234567'
mkdir -p "${work}/festa-unity/ci" "${work}/festa-unity/Builds/webgl/Build" "${work}/festa-unity/Builds/webgl/TemplateData" "${work}/festa-unity/Docker" "${work}/infra/jenkins/scripts" "${work}/infra/deploy/scripts" "${work}/art"
cp "${repo_root}/festa-unity/ci/package" "${work}/festa-unity/ci/package"
cp "${repo_root}/infra/jenkins/scripts/validate-webgl-archive.sh" "${work}/infra/jenkins/scripts/"
printf '#!/usr/bin/env bash\necho image-skipped\n' >"${work}/infra/deploy/scripts/package-local-image.sh"
out="${work}/festa-unity/Builds/webgl"
echo hi >"${out}/index.html"; echo x >"${out}/Build/a.loader.js"; echo y >"${out}/Build/b.data.unityweb"; echo z >"${out}/Build/c.framework.js.unityweb"; echo w >"${out}/Build/d.wasm.unityweb"; echo t >"${out}/TemplateData/s.css"
write_manifest(){ SHA="$1" DIRTY="$2" python3 - "${out}/manifest.json" <<'PY'
import json, os, sys
json.dump({'loaderUrl':'Build/a.loader.js','dataUrl':'Build/b.data.unityweb','frameworkUrl':'Build/c.framework.js.unityweb','codeUrl':'Build/d.wasm.unityweb',
  'sourceCommit':os.environ['SHA'],'sourceBranch':'develop','dirty':os.environ['DIRTY']=='true','unityVersion':'6000.0.78f1','buildProfile':'release'}, open(sys.argv[1],'w'))
PY
}
run_package(){ (cd "${work}/festa-unity" && CI_COMMIT_SHA="${sha}" CI_ARTIFACT_DIR="${work}/art" PATH="${work}/nolfs:${PATH}" bash ci/package); }
mkdir -p "${work}/nolfs"

write_manifest "${sha}" false
run_package >/dev/null || fail 'package should succeed'
zip="${work}/art/festa-webgl-release-${sha:0:8}.zip"; [[ -f "${zip}" && -f "${zip}.sha256" && -f "${work}/art/webgl-metadata.json" ]] || fail 'package outputs missing'
first="$(sha256sum "${zip}" | awk '{print $1}')"
sleep 1; touch "${out}/index.html" "${out}/Build/a.loader.js"
run_package >/dev/null || fail 'second package should succeed'
second="$(sha256sum "${zip}" | awk '{print $1}')"
[[ "${first}" == "${second}" ]] || fail "zip is not deterministic: ${first} != ${second}"
python3 - "${zip}" <<'PY' || fail 'zip contract'
import json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    infos = z.infolist()
    names = [i.filename for i in infos]
    assert names == sorted(names), names
    assert not any(n.endswith('/') for n in names), 'directory entries present'
    assert all(i.date_time == (1980, 1, 1, 0, 0, 0) for i in infos), 'timestamps not normalized'
    assert all((i.external_attr >> 16) == 0o100644 for i in infos), 'permissions not normalized'
    prov = json.loads(z.read('ci-provenance.json'))
    assert set(prov) == {'schemaVersion','component','sourceCommit','sourceBranch','unityVersion','buildProfile','lfsResolved'}, prov
    assert not any(k.lower().startswith('jenkins') for k in prov), 'execution provenance leaked into the zip'
PY
grep -q "${first}" "${work}/art/webgl-metadata.json" || fail 'webgl-metadata sha mismatch'

write_manifest "$(printf 'f%.0s' {1..40})" false
run_package >/dev/null 2>&1 && fail 'sourceCommit mismatch must be rejected'
write_manifest "${sha}" true
run_package >/dev/null 2>&1 && fail 'dirty manifest must be rejected'
write_manifest "${sha}" false

validator="${repo_root}/infra/jenkins/scripts/validate-webgl-archive.sh"
"${validator}" "${zip}" --source-commit "${sha}" >/dev/null || fail 'validator should accept'
"${validator}" "${zip}" --source-commit "$(printf 'e%.0s' {1..40})" >/dev/null 2>&1 && fail 'validator must reject wrong commit'
cp "${out}/manifest.json" "${work}/ext.json"; "${validator}" "${zip}" --manifest "${work}/ext.json" >/dev/null || fail 'validator external manifest'
python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); d['builtAt']='x'; json.dump(d,open(p,'w'))" "${work}/ext.json"
"${validator}" "${zip}" --manifest "${work}/ext.json" >/dev/null 2>&1 && fail 'validator must reject differing external manifest'
python3 - "${zip}" "${work}/nowasm.zip" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as src, zipfile.ZipFile(sys.argv[2], 'w') as dst:
    for i in src.infolist():
        if not i.filename.endswith('d.wasm.unityweb'): dst.writestr(i, src.read(i))
PY
"${validator}" "${work}/nowasm.zip" >/dev/null 2>&1 && fail 'validator must reject missing wasm entry'
echo 'PASS: WebGL package is deterministic, carries only source-stable provenance, and the archive validator rejects lineage/content drift'
