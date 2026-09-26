#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
validator="${repo_root}/infra/jenkins/scripts/validate-production-promotion.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

commit="$(git -C "${repo_root}" rev-parse HEAD)"
short="${commit:0:8}"
prefab_tree="$(
  git -C "${repo_root}" rev-parse \
    "${commit}:festa-unity/Assets/_Project/Prefabs"
)"

state="${work}/state"
webgl_root="${work}/webgl"

mkdir -p \
  "${state}/dev/batches/current" \
  "${state}/dev/batches/known-good" \
  "${state}/demo/game" \
  "${webgl_root}/releases/${short}"

COMMIT="${commit}" \
SHORT="${short}" \
PREFAB_TREE="${prefab_tree}" \
WORK="${work}" \
STATE="${state}" \
WEBGL_ROOT="${webgl_root}" \
python3 <<'PYFIXTURE'
import json
import os
import pathlib

commit = os.environ["COMMIT"]
short = os.environ["SHORT"]
prefab = os.environ["PREFAB_TREE"]
work = pathlib.Path(os.environ["WORK"])
state = pathlib.Path(os.environ["STATE"])
webgl_root = pathlib.Path(os.environ["WEBGL_ROOT"])

release_id = f"develop-{commit}-1"
# 부분 배포 조합: front 만 나중 배치에서 나왔다. 조합의 canonical ID 는 batchId 하나다 (Batch 1).
front_release_id = f"develop-{commit}-2"
batch_id = "demo-env-fixture000000001"

ids = {
    "ai": "sha256:" + "a" * 64,
    "back": "sha256:" + "b" * 64,
    "front": "sha256:" + "c" * 64,
    "game": "sha256:" + "d" * 64,
}

components = {
    name: {
        "releaseId": front_release_id if name == "front" else release_id,
        "sourceCommit": commit,
        "imageRef": f"festa-{name}:{commit}",
        "contentId": ids[name],
    }
    for name in ("ai", "back", "front", "game")
}

known_good_environment = {
    "schemaVersion": "1.0.0",
    "state": "KNOWN_GOOD",
    "batchId": batch_id,
    "approvedAt": "2026-09-19T00:01:00Z",
    "approvedBy": "fixture-approver",
    "approvedFromHistory": release_id,
    "components": components,
}

(state / "dev/batches/known-good/environment.json").write_text(
    json.dumps(known_good_environment, indent=2) + "\n"
)

release_manifest = {
    "schemaVersion": "1.0.0",
    "releaseId": release_id,
    "components": [
        {
            "name": name,
            "sourceCommit": commit,
            "imageRef": f"festa-{name}:{commit}",
            "contentId": ids[name],
        }
        for name in ("ai", "back", "front", "game")
    ],
}

for name in ("ai", "back", "front"):
    document = dict(release_manifest)
    if name == "front":
        # front 는 별도 배치의 current 다 — 미변경 컴포넌트를 재발급하지 않는다.
        document = {**release_manifest, "releaseId": front_release_id, "components": [
            item for item in release_manifest["components"] if item["name"] == "front"
        ]}
    (state / f"dev/batches/current/{name}.json").write_text(
        json.dumps(document, indent=2) + "\n"
    )

game_current = {
    "schemaVersion": "1.0.0",
    "targetId": "demo/game",
    "releaseId": release_id,
    "sourceCommit": commit,
    "imageRef": f"festa-game:{commit}",
    "contentId": ids["game"],
    "state": "CURRENT",
    "startedAt": "2026-09-19T00:00:00Z",
    "promotedAt": "2026-09-19T00:00:30Z",
}

game_known_good = dict(game_current)
game_known_good["state"] = "KNOWN_GOOD"
game_known_good["approvedAt"] = "2026-09-19T00:03:00Z"
game_known_good["approvedFromCurrent"] = release_id

(state / "demo/game/current.json").write_text(
    json.dumps(game_current, indent=2) + "\n"
)

(state / "demo/game/known-good.json").write_text(
    json.dumps(game_known_good, indent=2) + "\n"
)

webgl_package_url = (
    "https://lab.ssafy.com/api/v4/projects/1443023/packages/generic/"
    f"festa-webgl/{short}/festa-webgl-release-{short}.zip"
)

webgl_current = {
    "schemaVersion": "1.0.0",
    "releaseId": short,
    "artifactSha256": "e" * 64,
    "sourceCommit": commit,
    "sourceBranch": "develop",
    "packageUrl": webgl_package_url,
    "publicBaseUrl": "https://demo.example.invalid/unity",
    "verifiedVia": "edge",
    "recordedAt": "2026-09-19T00:00:45Z",
}

webgl_known_good = dict(webgl_current)
webgl_known_good["approvedAt"] = "2026-09-19T00:02:00Z"
webgl_known_good["approvedFromCurrent"] = short

(state / "dev/batches/current/webgl.json").write_text(
    json.dumps(webgl_current, indent=2) + "\n"
)

(state / "dev/batches/known-good/webgl.json").write_text(
    json.dumps(webgl_known_good, indent=2) + "\n"
)

manifest = {
    "schemaVersion": "1.0.0",
    "sourceCommit": commit,
    "sourceBranch": "develop",
    "dirty": False,
    "buildProfile": "release",
    "apiEnvironment": "Prod",
    "compression": "brotli+fallback",
}

(webgl_root / f"releases/{short}/manifest.json").write_text(
    json.dumps(manifest, indent=2) + "\n"
)

world_package_url = (
    "https://lab.ssafy.com/api/v4/projects/1443023/packages/generic/"
    f"festa-world/{short}/festa-world-release-{short}.tar"
)

receipt = {
    "schemaVersion": "1.0.0",
    "state": "APPROVED_FOR_PRODUCTION",
    "receiptId": "production-fixture-1",
    "approvedAt": "2026-09-19T00:10:00Z",
    "approvedBy": "fixture-operator",
    "sourceBranch": "develop",
    "demoReleaseId": batch_id,
    "applications": {
        name: components[name]
        for name in ("ai", "back", "front")
    },
    "webgl": {
        "packageName": "festa-webgl",
        "packageVersion": short,
        "packageUrl": webgl_package_url,
        "artifactSha256": "e" * 64,
        "sourceCommit": commit,
        "sourceBranch": "develop",
        "buildProfile": "release",
        "apiEnvironment": "Prod",
        "compression": "brotli+fallback",
    },
    "world": {
        "packageName": "festa-world",
        "packageVersion": short,
        "packageUrl": world_package_url,
        "archiveSha256": "f" * 64,
        "sourceCommit": commit,
        "sourceBranch": "develop",
        "imageRef": f"festa-game:{commit}",
        "imageContentId": ids["game"],
    },
    "compatibility": {
        "prefabTreeHash": prefab,
        "demoRuntimeVerified": True,
    },
    "evidence": {
        "environmentApprovedAt": "2026-09-19T00:01:00Z",
        "webglApprovedAt": "2026-09-19T00:02:00Z",
        "gameApprovedAt": "2026-09-19T00:03:00Z",
        "demoRuntimeVerificationRef": "fixture/demo-runtime.json",
    },
}

(work / "receipt.json").write_text(
    json.dumps(receipt, indent=2) + "\n"
)
PYFIXTURE

ln -s "releases/${short}" "${webgl_root}/current"

run_validator() {
  PRODUCTION_PROMOTION_RECEIPT_PATH="${work}/receipt.json" \
  ENVIRONMENT_STATE_DIR="${state}" \
  WEBGL_RELEASE_ROOT="${webgl_root}" \
  PYTHON_BIN=python3 \
    "${validator}"
}

output="$(run_validator)"
[[ "${output}" == "production-fixture-1" ]] \
  || fail "valid Production receipt was rejected"

# 정책 불일치는 경고로만 남기고 승격을 막지 않는다 (2026-09-21 운영 결정).
# 사실이 사라지면 안 되므로 stderr 의 WARN 과 정상 종료를 함께 본다.
expect_warn() {
  local label="$1" fragment="$2" stderr_file
  stderr_file="$(mktemp)"
  local stdout_value
  stdout_value="$(run_validator 2>"${stderr_file}")" \
    || { rm -f "${stderr_file}"; fail "${label}: validator stopped instead of warning"; }
  [[ "${stdout_value}" == "production-fixture-1" ]] \
    || { rm -f "${stderr_file}"; fail "${label}: validator did not emit the receipt id"; }
  grep -Fq "${fragment}" "${stderr_file}" \
    || { rm -f "${stderr_file}"; fail "${label}: validator did not warn"; }
  rm -f "${stderr_file}"
}

# Live WebGL drift warns.
mkdir -p "${webgl_root}/releases/drift000"
ln -sfn "releases/drift000" "${webgl_root}/current"

expect_warn 'live WebGL drift' 'live WebGL pointer differs from managed approved release'

ln -sfn "releases/${short}" "${webgl_root}/current"

# Named human approval is recorded as a warning when missing.
cp \
  "${state}/dev/batches/known-good/environment.json" \
  "${work}/environment-good.json"

python3 - "${state}/dev/batches/known-good/environment.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
doc = json.loads(path.read_text())
doc["approvedBy"] = None
path.write_text(json.dumps(doc, indent=2) + "\n")
PY

expect_warn 'anonymous Demo environment approval' 'Demo environment has no named human approver'

cp \
  "${work}/environment-good.json" \
  "${state}/dev/batches/known-good/environment.json"

# 조합 ID 는 batchId 하나다 — receipt 가 다른 batch 를 가리키면 경고로 남는다.
cp "${work}/receipt.json" "${work}/receipt-good.json"
python3 - "${work}/receipt.json" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1]); doc = json.loads(path.read_text())
doc["demoReleaseId"] = "demo-env-fixture000000002"
path.write_text(json.dumps(doc, indent=2) + "\n")
PY
expect_warn 'foreign Demo batchId' 'receipt demoReleaseId does not match Demo KNOWN_GOOD batchId'
cp "${work}/receipt-good.json" "${work}/receipt.json"

# 컴포넌트 releaseId 는 identity 검증에만 쓴다 — front 의 releaseId 가 KNOWN_GOOD 과 다르면 경고한다.
python3 - "${work}/receipt.json" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1]); doc = json.loads(path.read_text())
doc["applications"]["front"]["releaseId"] = doc["applications"]["ai"]["releaseId"]
path.write_text(json.dumps(doc, indent=2) + "\n")
PY
expect_warn 'foreign front releaseId' 'receipt front identity mismatch for releaseId'
cp "${work}/receipt-good.json" "${work}/receipt.json"

# Exact World image identity mismatch는 경고로 남는다 — 실제 이미지 판정은 deploy/verify 가 한다.
cp "${work}/receipt.json" "${work}/receipt-good.json"

python3 - "${work}/receipt.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
doc = json.loads(path.read_text())
doc["world"]["imageContentId"] = "sha256:" + "9" * 64
path.write_text(json.dumps(doc, indent=2) + "\n")
PY

expect_warn 'foreign World image' 'world package image content differs from Demo known-good game'

cp "${work}/receipt-good.json" "${work}/receipt.json"

# Schema state itself must be fail-closed.
python3 - "${work}/receipt.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
doc = json.loads(path.read_text())
doc["state"] = "ACTIVE"
path.write_text(json.dumps(doc, indent=2) + "\n")
PY

if run_validator >/dev/null 2>&1; then
  fail "invalid Production receipt state was accepted"
fi

cp "${work}/receipt-good.json" "${work}/receipt.json"

# T-299 — 자리표시(전부 0) World 체크섬은 경고가 아니라 전환 전에 멈춘다.
python3 - "${work}/receipt.json" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1]); doc = json.loads(path.read_text())
doc["world"]["archiveSha256"] = "0" * 64
path.write_text(json.dumps(doc, indent=2) + "\n")
PY
if run_validator >/dev/null 2>&1; then
  fail "placeholder World archive checksum was accepted"
fi
cp "${work}/receipt-good.json" "${work}/receipt.json"

pipeline="${repo_root}/infra/jenkins/pipelines/production-promotion.groovy"
python3 - "${pipeline}" <<'PY'
import pathlib,re,sys
text=pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
ordered=[
    "stage('Validate Receipt, Bootstrap and Main Ancestry')",
    "stage('Detect Idempotent Receipt')",
    "stage('Cutover Readiness Gate')",
    "stage('Maintenance Fence')",
    "stage('Replace Legacy with Canonical Candidate')",
    "stage('Verify Candidate')",
    "stage('Activate Public Production')",
    "stage('Verify Public Production')",
    "stage('Human Verification Gate')",
    "stage('Approve Known Good')",
    "stage('Archive Evidence')",
]
positions=[text.index(item) for item in ordered]
if positions!=sorted(positions): raise SystemExit('Production pipeline stage order mismatch')
if text.index('Human Verification Gate') > text.index('approve-production-known-good.sh'): raise SystemExit('known-good approval precedes human gate')
for required in ('PRODUCTION_ALREADY_APPROVED','prepare-production-cutover.sh','rollback-production-release.sh','secret-scan.sh --path artifacts'):
    if required not in text: raise SystemExit(f'Production pipeline missing {required}')

# find escape 는 이제 archiveProductionEvidence() 헬퍼 안에 있다.
archive = text.split("def archiveProductionEvidence()", 1)[1].split("\npipeline {", 1)[0]
if re.search(r'(?<!\\)\\[();]', archive):
    raise SystemExit('Archive Evidence shell escapes are not Groovy-safe')
for required in (r'\\(', r'\\)', r'\\;'):
    if required not in archive:
        raise SystemExit(f'Archive Evidence missing Groovy-safe shell escape: {required}')

# Executor 계약 (Batch 1): human gate 는 executor 를 잡지 않고, 실행 stage 는 각자 deploy node 와 checkout 을 확보한다.
if not re.search(r'^\s*agent none\s*$', text, re.M): raise SystemExit('Production pipeline must declare top-level agent none')
stages = re.split(r"\n        stage\('", text)[1:]
for block in stages:
    name = block.split("'", 1)[0]
    has_agent = "agent { label 'deploy' }" in block.split('\n            steps', 1)[0]
    is_gate = 'input(' in block
    if is_gate and has_agent: raise SystemExit(f'gate stage must not hold an executor: {name}')
    if not is_gate and not has_agent: raise SystemExit(f'executing stage must acquire the deploy agent itself: {name}')
    if is_gate and ('sh ' in block or "sh(" in block): raise SystemExit(f'gate stage must not run shell: {name}')
post = text.split('\n    post {', 1)[1]
if "node('deploy')" not in post or 'checkout scm' not in post: raise SystemExit('post block must acquire its own deploy node and checkout')
if 'archiveArtifacts' not in archive: raise SystemExit('evidence must be archived where it is produced')
PY

prod_state="${work}/production-state"
prod_webgl="${work}/production-webgl"
fake_bin="${work}/bin"
mkdir -p "${prod_state}/production/candidates" "${prod_state}/production/receipts" "${prod_webgl}/releases/${short}" "${prod_webgl}/prod" "${fake_bin}"
cp "${webgl_root}/releases/${short}/manifest.json" "${prod_webgl}/releases/${short}/manifest.json"
printf '%s\n' "$(python3 -c 'print("e"*64)')" >"${prod_webgl}/releases/${short}/.artifact-sha256"
ln -s "../releases/${short}" "${prod_webgl}/prod/candidate"
python3 - "${work}/receipt.json" "${prod_state}/production/candidates/production-fixture-1.verification.json" <<'PY'
import hashlib,json,pathlib,sys
r=pathlib.Path(sys.argv[1]); p=pathlib.Path(sys.argv[2])
p.write_text(json.dumps({'schemaVersion':'1.0.0','state':'VERIFIED','receiptId':'production-fixture-1','receiptSha256':hashlib.sha256(r.read_bytes()).hexdigest()})+'\n')
PY
cat >"${work}/active-prod.conf" <<'EOF_ACTIVE'
server { listen 443 ssl; server_name legacy.example.invalid; }
EOF_ACTIVE
cat >"${fake_bin}/nginx" <<'EOF_FAKE_NGINX'
#!/usr/bin/env bash
exit 0
EOF_FAKE_NGINX
cat >"${fake_bin}/reload" <<'EOF_FAKE_RELOAD'
#!/usr/bin/env bash
exit 0
EOF_FAKE_RELOAD
cat >"${fake_bin}/docker" <<'EOF_FAKE_DOCKER'
#!/usr/bin/env bash
if [[ "${1:-}" == inspect || "${1:-}" == container ]]; then exit 1; fi
if [[ "${1:-}" == image && "${2:-}" == inspect ]]; then
  ref="${@: -1}"
  case "${ref}" in
    festa-ai:*) printf 'sha256:%064s\n' a | tr ' ' a ;;
    festa-back:*) printf 'sha256:%064s\n' b | tr ' ' b ;;
    festa-front:*) printf 'sha256:%064s\n' c | tr ' ' c ;;
    festa-game:*) printf 'sha256:%064s\n' d | tr ' ' d ;;
    *) exit 1 ;;
  esac
  exit 0
fi
exit 0
EOF_FAKE_DOCKER
chmod +x "${fake_bin}/nginx" "${fake_bin}/reload" "${fake_bin}/docker"

common_env=(
  ENVIRONMENT_STATE_DIR="${prod_state}"
  WEBGL_RELEASE_ROOT="${prod_webgl}"
  ROOT_DOMAIN='example.invalid'
  PRODUCTION_WORLD_HOST='world-prod.example.invalid'
  NGINX_ORIGIN_CERTIFICATE_FILE='/fixture/origin.crt'
  NGINX_ORIGIN_PRIVATE_KEY_FILE='/fixture/origin.key'
  PRODUCTION_NGINX_CONFIG_PATH="${work}/active-prod.conf"
  PRODUCTION_WORLD_NGINX_CONFIG_PATH="${work}/active-world.conf"
  PRODUCTION_USE_SUDO=0
  NGINX_BIN="${fake_bin}/nginx"
  NGINX_RELOAD_BIN="${fake_bin}/reload"
  DOCKER_BIN="${fake_bin}/docker"
)

env "${common_env[@]}" "${repo_root}/infra/deploy/scripts/prepare-production-cutover.sh" "${work}/receipt.json" >/dev/null
[[ ! -e "${prod_state}/production/previous.json" ]] || fail 'first migration unexpectedly created previous state'
python3 - "${prod_state}/production/cutovers/production-fixture-1.prepare.json" <<'PY'
import json,pathlib,sys
d=json.loads(pathlib.Path(sys.argv[1]).read_text())
assert d['state']=='MAINTENANCE_ACTIVE' and d['previous'] is None and d['legacyRollbackAllowed'] is False
PY

cp "${prod_state}/production/candidates/production-fixture-1.verification.json" "${work}/verification-good.json"
python3 - "${prod_state}/production/candidates/production-fixture-1.verification.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); d=json.loads(p.read_text()); d['state']='RUNNING_UNVERIFIED'; p.write_text(json.dumps(d)+'\n')
PY
if env "${common_env[@]}" "${repo_root}/infra/deploy/scripts/activate-production-release.sh" "${work}/receipt.json" >/dev/null 2>&1; then
  fail 'unverified Production candidate was activated'
fi
cp "${work}/verification-good.json" "${prod_state}/production/candidates/production-fixture-1.verification.json"
env "${common_env[@]}" "${repo_root}/infra/deploy/scripts/activate-production-release.sh" "${work}/receipt.json" >/dev/null
[[ -f "${prod_state}/production/current.json" && ! -e "${prod_state}/production/known-good.json" ]] || fail 'CURRENT and KNOWN-GOOD were not separated'
# T-295 — 월드 인증서를 따로 주지 않으면 지금과 같은 원본 인증서로 렌더된다.
grep -Fq 'ssl_certificate /fixture/origin.crt;' "${work}/active-world.conf" || fail 'Production World vhost lost the origin certificate fallback'

if APPROVED_BY=fixture env "${common_env[@]}" "${repo_root}/infra/deploy/scripts/approve-production-known-good.sh" production-fixture-1 >/dev/null 2>&1; then
  fail 'Production known-good was approved without external verification'
fi
python3 - "${prod_state}/production/current.json" "${prod_state}/production/receipts/production-fixture-1.external-verification.json" <<'PY'
import hashlib,json,pathlib,sys
c=pathlib.Path(sys.argv[1]); p=pathlib.Path(sys.argv[2])
p.write_text(json.dumps({'schemaVersion':'1.0.0','state':'EXTERNAL_VERIFIED','receiptId':'production-fixture-1','currentSha256':hashlib.sha256(c.read_bytes()).hexdigest()})+'\n')
PY
cp "${prod_state}/production/current.json" "${work}/production-current-good.json"
python3 - "${prod_state}/production/current.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); d=json.loads(p.read_text()); d['publicActivatedAt']='changed-after-external-verification'; p.write_text(json.dumps(d)+'\n')
PY
if env APPROVED_BY=fixture-approver "${common_env[@]}" "${repo_root}/infra/deploy/scripts/approve-production-known-good.sh" production-fixture-1 >/dev/null 2>&1; then
  fail 'Production known-good accepted CURRENT changed after external verification'
fi
cp "${work}/production-current-good.json" "${prod_state}/production/current.json"
env APPROVED_BY=fixture-approver "${common_env[@]}" "${repo_root}/infra/deploy/scripts/approve-production-known-good.sh" production-fixture-1 >/dev/null
python3 - "${prod_state}/production/current.json" "${prod_state}/production/known-good.json" <<'PY'
import json,pathlib,sys
c,k=[json.loads(pathlib.Path(x).read_text()) for x in sys.argv[1:]]
assert c['state']=='CURRENT' and k['state']=='KNOWN_GOOD' and c['receiptId']==k['receiptId']
PY

# Once a canonical known-good exists, the next cutover captures it as previous.
env "${common_env[@]}" "${repo_root}/infra/deploy/scripts/prepare-production-cutover.sh" "${work}/receipt.json" >/dev/null
[[ -f "${prod_state}/production/previous.json" ]] || fail 'canonical previous state was not captured'

touch "${work}/back.env" "${work}/ai.env" "${work}/world.secret"
env "${common_env[@]}" \
  BACK_ENV_FILE="${work}/back.env" AI_ENV_FILE="${work}/ai.env" CONNECTION_TOKEN_SECRET_FILE="${work}/world.secret" \
  INTERNAL_SPRING_TO_AI_TOKENS=fixture-a INTERNAL_AI_TO_SPRING_TOKENS=fixture-b INTERNAL_INFRA_TO_SPRING_TOKENS=fixture-c \
  "${repo_root}/infra/deploy/scripts/rollback-production-release.sh" "${work}/receipt.json" canonical-fixture-failure >/dev/null
python3 - "${prod_state}/production/receipts/production-fixture-1.rollback.json" <<'PY'
import json,pathlib,sys
d=json.loads(pathlib.Path(sys.argv[1]).read_text())
assert d['result']=='ROLLED_BACK' and d['legacyRestored'] is False and d['previous']['state']=='CURRENT'
PY

# A first-migration failure has no previous and remains in maintenance; it never recreates legacy.
first_failure="${work}/first-failure-state"
mkdir -p "${first_failure}/production/receipts"
cp "${prod_state}/production/current.json" "${first_failure}/production/current.json"
env "${common_env[@]}" ENVIRONMENT_STATE_DIR="${first_failure}" "${repo_root}/infra/deploy/scripts/rollback-production-release.sh" "${work}/receipt.json" fixture-failure >/dev/null
[[ ! -e "${prod_webgl}/prod/current" ]] || fail 'first migration failure left a Production current WebGL pointer'
python3 - "${first_failure}/production/receipts/production-fixture-1.rollback.json" <<'PY'
import json,pathlib,sys
d=json.loads(pathlib.Path(sys.argv[1]).read_text())
assert d['previous'] is None and d['result']=='MAINTENANCE_REQUIRED' and d['legacyRestored'] is False
PY

echo "PASS: Production promotion receipt binds human-approved Demo app, WebGL, World and prefab identity"
