# Data Model: 변경 컴포넌트 CI와 dev 배포

운영 모델이다. GitLab CI pipeline record와 Jenkins artifact·EC2 runtime state로만 저장한다. Secret 값은 포함하지 않는다.

## PipelineDefinition

| Field | Rule |
|---|---|
| `executionKind` | `gitlab-mr-gate`, `jenkins-develop-dev`, `jenkins-demo-promotion` |
| `sourcePolicy` | `feature-to-develop-front-back`, `develop-push`, `approved-dev-release` |
| `allowedComponents` | `ai`, `back`, `front`, `game` |
| `deployEnabled` | only `jenkins-develop-dev` and `jenkins-demo-promotion` |

## ChangeSet

| Field | Rule |
|---|---|
| `baseSha`, `headSha` | Jenkins develop only: full SHA and exact diff range consumed by detector |
| `eventKind` | `gitlab_merge_request` or `jenkins_develop_push` |
| `paths` | changed repository paths; secret values excluded |
| `components` | ordered unique component list |
| `selectionReason` | `component-source`, `component-runtime-config`, `shared-ci`, `docs-only` |
| `deployComponents` | same as `components` only for a runtime/component change on `develop_push`; otherwise empty |

Validation: GitLab MR selection uses `rules:changes` only for Front·Back. Jenkins unknown in-scope runtime/deploy paths fail closed. Jenkins `shared-ci` selects all CI components and never adds deploy components. `docs-only` selects neither.

## BuildArtifact

Existing immutable image/web bundle record: `component`, `sourceCommit`, `imageRef`, `contentId`, `producerRunId`. `contentId` must match Docker image ID before deployment.

## DevDeploymentBatch

| Field | Rule |
|---|---|
| `batchId` | Jenkins run ID; unique |
| `changeSet` | one ChangeSet reference |
| `components` | all selected deploy components; never partial after CI gate |
| `candidates` | component → immutable BuildArtifact |
| `beforeState` | component → current/known-good release pointer captured under batch lock |
| `status` | `CANDIDATE`, `DEPLOYING`, `ACTIVE`, `ROLLING_BACK`, `ROLLED_BACK`, `MANUAL_ACTION_REQUIRED` |
| `verification` | per-component result and evidence ref |

Transitions:

```text
CANDIDATE → DEPLOYING → ACTIVE
                     └→ ROLLING_BACK → ROLLED_BACK
                                       └→ MANUAL_ACTION_REQUIRED
                     └→ MANUAL_ACTION_REQUIRED
```

`ACTIVE` is allowed only when every component has passed verification. Rollback restores only components recorded in `beforeState` and already changed by this batch.

## DevComponentState

`component`, `currentReleaseId`, `knownGoodReleaseId`, `contentId`, `updatedByBatchId`, `verifiedAt`. A component pointer updates after its own verification; the batch marker is commit-ready only at batch `ACTIVE`.

## DemoPromotion

`promotionId`, `approvedBy`, `sourceDevBatchId`, `releaseManifest`, `status`, `verificationEvidence`. It is valid only when source batch is `ACTIVE` and all requested runtime components are dev-verified. A `develop` push cannot create this model automatically.

## WebGLPackageDeployment

| Field | Rule |
|---|---|
| `releaseId` | Generic Package version과 동일한 immutable ID |
| `artifactSha256` | publisher가 전달하고 Jenkins가 다운로드 후 재계산한 64자리 SHA-256 |
| `packageUrl` | `festa-webgl/<releaseId>/festa-webgl-release-<releaseId>.zip`; credential 제외 |
| `releasePath` | `/srv/festa/webgl/releases/<releaseId>`; 같은 ID에 다른 SHA 금지 |
| `current`, `previous` | 관리되는 release만 가리키는 상대 symbolic link |
| `status` | `INSTALLED`, `ACTIVE`, `ROLLED_BACK`, `FAILED` |
| `verification` | index·manifest·참조 Build 파일의 HTTP, MIME, Brotli, Cache-Control 결과 |

동일 release ID와 SHA 재호출은 멱등이다. 공개 검증에 실패하면 `current`를 전환 전 값으로 되돌리고 candidate는 진단용으로 격리 보존한다.
