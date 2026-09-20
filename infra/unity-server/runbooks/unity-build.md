# Unity build / publish 자동화 운영 (Batch 2)

Jira: `S15P21A604-939`. develop 에 Unity 입력(`festa-unity/`, `ci/`, `infra/jenkins/agents/`, `.gitattributes` 등 detector 의
`gameBuildRequired`)이 merge 되면 `festa-gitlab-develop/develop` 이 사람 개입 없이 다음을 한다.

```text
Resolve Game Artifacts (deploy agent)
  festa-webgl/<8sha> + festa-world/<8sha> 둘 다 Registry 에 있음 → SKIP_TO_DEPLOY
  둘 다 없음 + 로컬 zip/image 없음                            → BUILD_REQUIRED
  둘 다 없음 + 로컬 zip/image 있음                            → PUBLISH_BOTH
  한쪽만 있음 + 없는 쪽이 로컬에 있음                          → PUBLISH_WEBGL / PUBLISH_WORLD
  한쪽만 있음 + 없는 쪽이 로컬에도 없음                        → exit 65 PARTIAL_REGISTRY (rebuild 금지, 사람이 본다)
Producer (unity agent, BUILD_REQUIRED 일 때만)
  git lfs pull → pointer 잔존 시 78 · dirty 시 65 · preflight-license 실패 시 79
  festa-unity/ci/build --target webgl → --target linux-server (한 checkout, 두 세션)
  festa-unity/ci/package: 결정적 zip(+ci-provenance.json) + festa-game:<sha40> image → /var/lib/festa-image-transfer/webgl/<8sha>/
Consumer (deploy agent)
  check-game-source-identity.sh: commit 이 origin 에 있고 origin/develop 조상, zip dirty=false, image label 일치
  publish-webgl-release.sh --no-trigger / publish-world-release.sh: 같은 version+같은 SHA → *_RELEASE_EXISTS, 다른 SHA → 65
  validate-game-release-set.sh → deploy-game.sh(candidate World) → game-readiness.sh → deploy-webgl-release.sh(current flip) → promote-game.sh
```

VERIFIED(readiness) 전 실패는 `/srv/festa/webgl/current` 와 `dev/batches/current/*.json` 을 바꾸지 않는다. World runtime 만 잠시
candidate 였다가 `rollback-game.sh` 로 돌아온다. WebGL 활성화가 자체 verify 에 실패하면 `deploy-webgl-release.sh` 가 previous 로
되돌리고 파이프라인이 World 도 되돌린다.

## 종료 코드

| code | 의미 | 조치 |
|---|---|---|
| 79 | `UNITY_LICENSE_INVALID` — entitlement 가 이 agent identity 에 없음 | 아래 라이선스 재활성화 |
| 78 | `LFS_POINTER_UNRESOLVED` | LFS 서버/토큰 확인 후 rerun (rebuild 아님) |
| 65 | identity/lineage 불일치, `PACKAGE_IDENTITY_COLLISION`, `PARTIAL_REGISTRY`, `SOURCE_NOT_IN_REPOSITORY` | 원인 확인. Registry 는 덮어쓰지 않는다 |
| 75 | prefab set 불일치로 World 교체 건너뜀 | 정상 skip |

## 라이선스 재활성화 (MANUAL GATE)

Unity Personal entitlement 는 컨테이너 hostname/machine binding 에 묶인다. 2026-09-19 recreate 로 hostname 이 바뀌자 파일은
그대로인데 `Unity.Licensing.Client --showEntitlements` 가 `No licenses were found.` 를 냈다(#498/#504/#505, exit 198).
`infra/jenkins/agents/compose.yaml` 의 `hostname: festa-unity-agent` / `mac_address` 고정이 그 재발을 막는다.

```bash
# 1. identity 고정 반영 (배포가 돌지 않는 시점)
cd ~/festa/S15P21A604 && set -a && source infra/.env && set +a
docker compose --env-file infra/.env -f infra/jenkins/agents/compose.yaml --profile unity up -d unity-agent

# 2. 컨테이너 안에서 사람이 1회 재활성화 — 자격증명은 저장하지 않는다 (FR-017/018)
docker exec -it festa-jenkins-agent-unity bash
C=/opt/unity/editors/6000.0.78f1/Editor/Data/Resources/Licensing/Client/Unity.Licensing.Client
$C --help            # 설치된 버전의 옵션을 먼저 본다 (1.17.4: --activateSession / --syncEntitlements / --username --password)
$C --showContext     # 지금 identity (hostname/machine binding)
# 기존 절차(infra/evidence/unity-agent-preflight.md §2)대로 Unity 계정으로 활성화한다.
$C --showEntitlements   # Unity Personal 이 보여야 한다

# 3. 판정: LICENSE_BINDING_STABLE = preflight PASS → recreate #1 PASS → recreate #2 PASS
docker exec festa-jenkins-agent-unity bash /home/jenkins/agent/unity/workspaces/develop-game/festa-unity/ci/preflight-license
docker compose --env-file infra/.env -f infra/jenkins/agents/compose.yaml --profile unity up -d --force-recreate unity-agent   # ×2
```

MR A merge 직후 develop 빌드가 79 로 멈추는 것은 의도된 migration 단계다. 위 절차 뒤 **같은 SHA** 를 Jenkins 에서 Rebuild 한다
(README 수정 같은 재트리거 커밋을 만들지 않는다).

## Git LFS

`.gitattributes` 가 `festa-unity/**/*.{fbx,tga,psd,mp3,exr,skp}` 를 LFS 로 추적한다(개별 line). history 는 다시 쓰지 않았다 —
이미 커밋된 파일은 다음 변경부터 LFS 에 들어간다. 개발자는 `git lfs install` 한 번, clone 뒤 `git lfs pull`. Jenkins unity
agent 는 checkout 마다 `git lfs pull` 하고 pointer 가 남으면 78 로 멈춘다.

## Registry 계약

`festa-webgl/<8sha>/{festa-webgl-release-<8sha>.zip,.zip.sha256,festa-webgl-release-<8sha>.json}`,
`festa-world/<8sha>/{...tar,.tar.sha256,...json}`. version = source SHA 앞 8자. zip 안 `ci-provenance.json` 은 source-stable
값(sourceCommit/sourceBranch/unityVersion/buildProfile/lfsResolved)만, `.json` sidecar 는 실행 provenance(jenkinsJob/BuildNumber/
BuildUrl/builderClass/publishedAt). 같은 version 에 다른 바이너리는 게시되지 않는다(65). 과거 12/40자 version, `test/0.0.1`,
`ssafesta-unity-release/upload-test-*` 는 STALE_OR_TEST — 삭제는 Batch 3.

## Historical / Fallback

Unity Editor 메뉴 Release Build, 수동 zip 업로드, `festa-webgl-package-deploy` 수동 실행, `festa-world-package-publish` 수동
파라미터 입력은 정상 경로가 아니다. Registry 에 있는 release 를 손으로 다시 활성화할 때만 `festa-webgl-package-deploy` 를 쓴다.

## 자원 증거

매 Unity 빌드는 `artifacts/develop/build/game/resource-evidence.json` (loadavg·MemAvailable·PSI cpu/memory/io 증분·disk sectors·
workspace/Library/Builds 크기·duration·artifact 크기)을 남긴다. Runner/host 용량 결정은 이 파일의 누적치로만 한다.
