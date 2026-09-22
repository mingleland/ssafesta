# Unity Release Bundle → Registry → Demo (Batch 2 Consumer-only)

Jira: `S15P21A604-939`. **Jenkins 는 Unity Editor 를 돌리지 않는다.** Unity 라이선스·계정·entitlement 는 CI 인프라가 관리하지 않는다.
WebGL 클라이언트와 Linux Dedicated Server 는 Unity 담당자의 정상 개발환경에서 만들어지고, Jenkins 는 그 산출물(Unity Release Bundle)
이후만 자동화한다: source identity → artifact 검증 → canonical Registry publish → compatibility → Demo candidate → readiness → current.

## 1. Unity 담당자가 하는 일 (producer)

develop 에 도달한 commit 을 clean checkout 하고(추적 파일 변경 없음, `git lfs pull` 완료) 다음 4 파일을 만든다 — 이미 실물로 전달한 형식 그대로.

```text
festa-webgl-release-<8sha>.zip     index.html · manifest.json · Build/ · TemplateData/ (ci-provenance.json 은 있어도 없어도 된다)
festa-game-<8sha>.tar              docker image save festa-game:<sha40> (label org.ssafy-festa.{component=game,source-commit,managed=true})
webgl-manifest.json                zip 안 manifest.json 과 byte 동일 (sourceCommit=<sha40>, sourceBranch=develop, dirty=false, buildProfile=release, unityVersion=6000.0.78f1)
image-metadata.json                {"schemaVersion":"1.0.0","component":"game","sourceCommit":"<sha40>","storageMode":"local-docker","imageRef":"festa-game:<sha40>","contentId":"sha256:…"}
```

repo 의 로컬 어댑터가 같은 형식을 만든다: `ci/build`(webgl → linux-server) → `festa-unity/ci/package`(결정적 zip + `.sha256` + `webgl-metadata.json`, image + `image-metadata.json`).
`contentId` 는 `docker image inspect --format '{{.Id}}'` 값이다(containerd store 면 OCI index digest, legacy store 면 config digest — 둘 다 허용).

게시·Demo 배포 시작의 유일한 경로는 저장소 helper 다. 담당자 본인의 package write token과 Demo deploy 권한이 있는 Jenkins API token을 환경변수로만 주입하며, token 은 어디에도 저장하지 않는다.

```bash
GITLAB_PACKAGE_TOKEN=... \
JENKINS_URL=https://ci.ssafesta.world \
JENKINS_USER=... \
JENKINS_API_TOKEN=... \
infra/jenkins/scripts/publish-unity-release-bundle.sh \
  --bundle-dir <4파일이_있는_디렉터리> \
  --source-commit <40자리_Git_SHA>
```

helper 는 로컬 계약 검증 → 4파일과 `bundle.sha256`의 멱등 게시 → `festa-gitlab-develop/develop` 실행
(`UNITY_ARTIFACT_CANDIDATE=<SHA>`, `DEPLOY_GAME_TO_DEMO=true`)까지 한 번에 수행한다. Registry API에 4파일만 직접 올리면 Jenkins 이벤트가 생기지 않으므로 정상 게시 경로로 쓰지 않는다.

## 2. Jenkins 가 하는 일 (consumer, `festa-gitlab-develop/develop`, deploy agent)

세 identity 가 분리된다:
- `pipelineCommit` = develop HEAD (`CI_COMMIT_SHA` 는 불변).
- `artifactSourceCommit` = Unity 번들의 빌드 커밋 `S`. `releaseId` 는 `S[:8]`.
- `unityInputId` = sha256(`tree=${tree}|unityVersion=${v}|unityRevision=${r}|buildProfile=release|apiEnvironment=Prod|artifactContract=manifest-1.0.0`). 두 ID 가 같을 때 content-equivalent 재사용.

```text
Resolve  resolve-game-artifacts.sh --pipeline-commit <sha40> [--candidate <sha>]
           1. exact: unitySourceSha(HEAD 기준 festa-unity 마지막 변경 커밋) 조회
           2. candidate: --candidate SHA 조회 (558d6624 등 명시 지정 시)
           3. recent: 최근 5개 registry 패키지 중 unityInputId 동일분 조회
           festa-webgl/<8sha> + festa-world/<8sha> 둘 다 있음 → REGISTRY_COMPLETE (재사용)
           둘 다 없음 + unity-release-bundle/<8sha> 있음   → BUNDLE_AVAILABLE
           한쪽만 있음 + bundle 있음                        → PUBLISH_WEBGL / PUBLISH_WORLD (없는 쪽만)
           둘 다 없음 + bundle 없음                         → WAITING_FOR_UNITY_ARTIFACT (정상 종료, 아무것도 만들지 않음)
           한쪽만 있음 + bundle 없음                        → exit 65 PARTIAL_REGISTRY (사람이 본다)
Intake   intake-unity-release-bundle.sh: 4 파일 download → zip(validate-webgl-archive) · tar(validate-game-image-archive, load 없이) ·
           metadata 교차검증 → docker image load → 로드된 image .Id/label 재확인
Gate     check-game-source-identity.sh: git fetch origin develop → cat-file → unityInputId 일치 확인 → zip dirty=false → image label
Publish  publish-webgl-release.sh --no-trigger / publish-world-release.sh (같은 version 같은 SHA → *_RELEASE_EXISTS, 다른 SHA → 65)
Verify   validate-game-release-set.sh (zip sha · image contentId · 같은 commit)
Deploy   deploy-game.sh(candidate World) → game-readiness.sh(wss://demo.<root>/) → deploy-webgl-release.sh(current flip) → promote-game.sh
```

bundle 이 아직 없으면 빌드는 `WAITING_FOR_UNITY_ARTIFACT` 로 끝난다. 이후 publisher helper가 bundle을 게시하고 같은 source commit을 candidate로 지정한 develop Demo 배포를 자동 시작한다.
VERIFIED(readiness) 전 실패는 `/srv/festa/webgl/current`·`dev/batches/current/*.json` 을 바꾸지 않는다; candidate World runtime 만
`rollback-game.sh` 로 돌아온다. WebGL 활성화 실패는 `deploy-webgl-release.sh` 가 previous 로 되돌리고 파이프라인이 World 도 되돌린다.

## 3. 종료 코드

| code | 의미 | 조치 |
|---|---|---|
| 65 | identity/lineage 불일치, `PACKAGE_IDENTITY_COLLISION`, `PARTIAL_REGISTRY`, `SOURCE_NOT_IN_REPOSITORY`, `BUNDLE_*_MISMATCH` | 원인 확인. Registry 는 덮어쓰지 않는다 |
| 66 | `BUNDLE_INCOMPLETE` — 4 파일 중 일부 없음 | 담당자에게 누락 파일 업로드 요청 |
| 75 | prefab set 불일치로 World 교체 건너뜀 | 정상 skip |

## 4. Registry 계약

`unity-release-bundle/<8sha>` = intake(담당자 업로드). canonical = `festa-webgl/<8sha>/{zip,.zip.sha256,.json}`, `festa-world/<8sha>/{tar,.tar.sha256,.json}`.
version 은 source SHA 앞 8자. zip 안 `ci-provenance.json`(있다면)은 source-stable 값만, `.json` sidecar 는 실행 provenance(jenkinsJob/BuildNumber/
BuildUrl/builderClass/publishedAt). 과거 12/40자 version, `test/0.0.1`, `ssafesta-unity-release/upload-test-*` 는 STALE_OR_TEST — 삭제는 Batch 3.

## 5. Historical / Fallback / 범위 밖

- Registry API 직접 업로드와 develop job 수동 재실행은 publisher 장애 시 Infra 담당자가 사용하는 복구 절차일 뿐 정상 게시 경로가 아니다.
- `festa-unity-bundle-e2e` 는 직전 Demo 상태로 반드시 복구하는 수동 검증 job이며 publisher가 호출하지 않는다.
- `festa-webgl-package-deploy` 수동 실행과 `festa-world-package-publish` 수동 파라미터는 fallback 이다.
- Jenkins unity agent 의 Unity Personal entitlement(T-169)는 CI 의 blocker 가 아니다. `festa-unity-mr-validation`(EditMode) 은 여전히 그 agent 를 쓰며 별건이다.
- Unity Cloud Build / UBA / 별도 Unity 계정 자동화 / floating license 는 도입하지 않는다.
- 외부에서 받은 artifact 의 sourceCommit 이 repository 에 없으면(`5f148b69`, T-170) canonical publish 할 수 없다 — 담당자는 develop 에 도달한 commit 에서만 만든다.
