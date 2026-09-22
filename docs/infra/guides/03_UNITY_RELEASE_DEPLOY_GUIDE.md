# Unity 담당자를 위한 Release / Demo 배포 가이드

> 대상: Unity 담당자  
> 이 문서는 현재 `develop`의 실제 정본·runbook·스크립트를 기준으로 작성했습니다.  
> 계약 충돌 시 다음이 우선합니다.
>
> - [`../canonical/02_CURRENT_CICD_RUNTIME_ARCHITECTURE.md`](../canonical/02_CURRENT_CICD_RUNTIME_ARCHITECTURE.md)
> - [`../../../infra/unity-server/runbooks/unity-release-bundle.md`](../../../infra/unity-server/runbooks/unity-release-bundle.md)

---

## 1. 제일 먼저: MR 검증과 Release는 다른 일입니다

### A. Unity MR Validation

Unity 코드를 MR로 올리면:

```text
GitLab CI
`unity-mr-validation-dispatch`
↓
Jenkins `festa-unity-mr-validation`
↓
Unity Agent
↓
Unity Editor 6000.0.78f1 EditMode Test
↓
`unity-mr-validation` commit status
```

이 경로는 **컴파일/EditMode 검증 전용**입니다.

> 여기서는 Release WebGL/Game artifact를 만들거나 배포하지 않습니다.

### B. 실제 Unity Release

```text
Unity 담당자 licensed 개발환경
↓
WebGL + Linux Dedicated Server build
↓
Release Bundle 4파일
↓
GitLab Generic Package Registry
↓
Jenkins Consumer
↓
검증 / publish / Demo candidate / readiness / current
```

> Jenkins는 **Unity Release artifact를 빌드하지 않습니다.**

---

## 2. Release를 만들기 전 체크

Release Bundle은 **develop에 실제로 존재하는 commit**에서 만들어야 합니다.

```text
[ ] 배포 대상 commit이 GitLab develop 이력에 존재
[ ] clean checkout
[ ] 추적 파일 변경 없음 (`dirty=false`)
[ ] 필요한 LFS 파일 수신 완료
[ ] Unity 6000.0.78f1 기준
[ ] release profile
```

GitLab 저장소에 존재하지 않는 source commit으로 만든 artifact는 무결성이 좋아도 canonical publish 대상이 될 수 없습니다.

### 558d6624 사례

`558d6624` bundle은 artifact 자체와 Demo E2E는 통과했지만 source commit이 GitLab에 없었습니다.

따라서:

```text
fixture only
CANONICAL_PROVENANCE_PENDING
Production 사용 금지
```

입니다.

---

## 3. Release Bundle 4파일

현재 정본의 정확한 파일명입니다. `<8sha>`는 source commit 앞 8자리입니다.

```text
festa-webgl-release-<8sha>.zip
festa-game-<8sha>.tar
webgl-manifest.json
image-metadata.json
```

### `festa-webgl-release-<8sha>.zip`

주요 구성:

```text
index.html
manifest.json
Build/
TemplateData/
```

`ci-provenance.json`은 있어도 없어도 됩니다.

### `festa-game-<8sha>.tar`

`docker image save festa-game:<sha40>` 형식의 Game image archive입니다.

Image에는 최소한 source commit lineage를 확인할 수 있는 label 계약이 적용됩니다.

### `webgl-manifest.json`

WebGL zip 내부 `manifest.json`과 byte-identical이어야 하며 주요 값은 다음과 같습니다.

```text
sourceCommit = <sha40>
sourceBranch = develop
dirty = false
buildProfile = release
unityVersion = 6000.0.78f1
```

### `image-metadata.json`

Game image identity를 설명합니다.

핵심 필드:

```json
{
  "schemaVersion": "1.0.0",
  "component": "game",
  "sourceCommit": "<sha40>",
  "storageMode": "local-docker",
  "imageRef": "festa-game:<sha40>",
  "contentId": "sha256:..."
}
```

---

## 4. 가장 쉬운 게시 방법: 저장소 helper 사용

현재 저장소에는 다음 helper가 있습니다.

```text
infra/jenkins/scripts/publish-unity-release-bundle.sh
```

기본 사용법:

```bash
infra/jenkins/scripts/publish-unity-release-bundle.sh \
  --bundle-dir <4파일이_있는_디렉터리> \
  --source-commit <40자리_Git_SHA>
```

기본 target은 `demo`입니다.

필요 credential/env:

```text
GITLAB_PACKAGE_TOKEN
JENKINS_URL
JENKINS_USER
JENKINS_API_TOKEN
```

토큰 값은 저장소·문서·로그에 기록하지 않습니다.

이 helper는:

```text
로컬 4파일 계약 검증
↓
Registry 기존 version 검사
↓
같은 bytes면 BUNDLE_EXISTS
다른 bytes면 hard stop
↓
신규면 업로드
↓
Jenkins develop Demo deploy 자동 trigger
```

까지 수행합니다.

### 중요한 멱등성 규칙

같은 `<8sha>`에:

```text
같은 bytes
→ 정상 재실행 가능 (`BUNDLE_EXISTS`)

다른 bytes
→ `BUNDLE_IDENTITY_COLLISION`
→ exit 65
→ 절대 덮어쓰지 않음
```

이미 올린 version을 수정해서 덮는 방식은 금지입니다.

---

## 5. 직접 Registry 업로드는 정상 경로가 아니다

Registry intake 위치는 다음이지만 Unity 담당자가 API로 직접 업로드하지 않습니다.

```text
GitLab Generic Package Registry
unity-release-bundle/<8sha>
```

직접 업로드는 GitLab 이벤트나 Jenkins 배포를 만들지 않습니다. `bundle.sha256`과 실제 Demo deploy trigger까지 책임지는
`publish-unity-release-bundle.sh`만 정상 게시 경로입니다. 직접 API 업로드는 publisher 장애 시 Infra 담당자의 복구 절차로만 다룹니다.

---

## 6. 게시 후 Jenkins가 자동으로 하는 일

publisher가 시작한 `festa-gitlab-develop/develop`은 다음 순서로 처리합니다.

```text
1. Release Bundle download
2. WebGL zip 구조/manifest 검증
3. Game tar/image metadata 검증
4. WebGL과 Game sourceCommit 일치 확인
5. Game image load + contentId / label 재검증
6. Git source identity / unityInputId gate
7. canonical Registry publish
8. WebGL/Game release set 검증
9. Demo World candidate 배포
10. readiness / WSS 검증
11. WebGL current flip
12. World promote
```

Jenkins 서버에서 Unity Editor로 다시 빌드하지 않습니다.

---

## 7. Unity identity가 FE/BE merge 때문에 깨지지 않는 이유

현재 identity는 세 축으로 분리됩니다.

```text
pipelineCommit
artifactSourceCommit
unityInputId
```

따라서 Unity가 바뀌지 않은 상태에서 FE/BE/CI commit이 develop HEAD에 추가됐다고 같은 Unity artifact를 무조건 다시 만들지 않습니다.

Consumer는 exact/candidate/recent lookup을 통해 이미 canonical Registry에 존재하는 content-equivalent artifact를 재사용할 수 있습니다.

---

## 8. Demo에서 확인할 것

Consumer가 정상 완료된 뒤 팀 Demo에서 확인합니다.

```text
https://demo.ssafesta.world
```

Unity 관점 확인:

```text
WebGL 정상 로드
월드 진입
캐릭터 이동
네트워크 연결
부스 / 상호작용
변경한 Unity 기능
```

World 외부 연결은 Demo 17777 경로와 WSS 101 probe로 검증됩니다.

문제가 있을 때 Unity 담당자가 서버를 직접 고칠 필요는 없습니다. 다음 정보와 함께 인프라 담당자에게 전달하면 됩니다.

```text
source commit SHA
bundle <8sha>
Jenkins build 번호
실패 gate / stage
브라우저에서 보이는 증상
```

---

## 9. 대표 실패 의미

| 증상 / 코드 | 의미 | 우선 조치 |
|---|---|---|
| `SOURCE_NOT_IN_REPOSITORY` | source commit이 canonical Git 이력에 없음 | push/develop 도달 여부 확인 |
| `BUNDLE_INCOMPLETE` / 66 | 4파일 중 누락 | 같은 `<8sha>` bundle 파일 확인 |
| `BUNDLE_*_MISMATCH` / 65 | manifest/image/source lineage 불일치 | 원본 build 결과와 metadata 확인 |
| `BUNDLE_IDENTITY_COLLISION` / 65 | 같은 version에 다른 bytes | 덮어쓰지 말고 원인 조사 |
| `PARTIAL_REGISTRY` / 65 | canonical WebGL/World 한쪽만 존재 | 인프라 담당자 확인 |
| 75 | prefab set 불일치로 World 교체 skip | 정본상 정상 skip 가능 |
| EditMode validation 실패 | Unity MR 코드/테스트 문제 | MR 수정 |
| Demo readiness 실패 | candidate Game runtime/WSS 문제 | Jenkins stage와 infra 확인 |

---

## 10. 실패 시 자동 보호

Consumer는 검증 전 실패했다고 current를 함부로 바꾸지 않습니다.

```text
VERIFIED 전 실패
→ candidate World rollback
→ current WebGL 유지

WebGL 활성화 실패
→ WebGL previous 복구
→ World도 rollback
```

Batch 2 E2E에서는 fixture candidate를 실제 Demo에 올렸다가 `finally`에서 이전 World/WebGL로 자동 복구하는 경로까지 검증했습니다.

---

## 11. 절대 하지 말 것

```text
❌ EC2/Jenkins에서 Unity Release build 시도
❌ CI 인프라에 Unity Personal 로그인/인증을 Release 조건으로 추가
❌ 같은 <8sha> artifact 덮어쓰기
❌ GitLab에 없는 commit을 canonical Production artifact로 사용
❌ Demo Game container 직접 교체
❌ Production Registry/runtime 직접 수정
```

---

## 12. 참고 정본

- 현재 CI/CD 구조: [`../canonical/02_CURRENT_CICD_RUNTIME_ARCHITECTURE.md`](../canonical/02_CURRENT_CICD_RUNTIME_ARCHITECTURE.md)
- Batch 2 완료 근거: [`../canonical/03_BATCH_1_2_3_COMPLETION_EVIDENCE.md`](../canonical/03_BATCH_1_2_3_COMPLETION_EVIDENCE.md)
- 운영 불변조건: [`../canonical/05_OPERATIONS_RUNBOOK_AND_INVARIANTS.md`](../canonical/05_OPERATIONS_RUNBOOK_AND_INVARIANTS.md)
- 상세 Release Bundle runbook: [`../../../infra/unity-server/runbooks/unity-release-bundle.md`](../../../infra/unity-server/runbooks/unity-release-bundle.md)
- 게시 helper: [`../../../infra/jenkins/scripts/publish-unity-release-bundle.sh`](../../../infra/jenkins/scripts/publish-unity-release-bundle.sh)
