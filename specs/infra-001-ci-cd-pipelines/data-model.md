# Data Model: 파트별 CI/CD 파이프라인

이 기능은 업무 DB 테이블을 추가하지 않는다. 아래 모델은 Jenkins artifact, release manifest, 배포 target의 작은 상태 파일, Grafana provisioning 계약으로 저장되는 운영 데이터다. Secret 값은 어떤 모델에도 포함하지 않는다.

## 1. PipelineDefinition

파트 또는 통합 파이프라인의 버전 관리 정의다.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `id` | string | 안정적인 고유 ID |
| `scope` | enum | `ai`, `back`, `front`, `game`, `develop` |
| `scmProvider` | enum | `github`, `gitlab`; runtime job 설정이며 pipeline 로직에 분기 최소화 |
| `stages` | array | validate/test/build/package/deploy/verify 등의 순서 |
| `targetId` | string | 배포 lock과 연결 |
| `requiredAgentLabels` | string[] | controller label 금지 |
| `contractVersion` | string | component adapter 계약 버전 |

## 2. PipelineRun

한 commit에 대한 실행 기록이다.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `runId` | string | Jenkins job + build number로 유일 |
| `pipelineId` | string | PipelineDefinition 참조 |
| `branch` / `commit` | string | commit은 full SHA |
| `trigger` | object | provider/event/actor; credential 값 제외 |
| `stageResults` | array | stage, status, startedAt, finishedAt, evidenceRef |
| `artifactRefs` | string[] | BuildArtifact 참조 |
| `releaseId` | string? | package 이후 생성될 때만 |
| `status` | enum | 아래 상태 전이 |

상태 전이:

```text
QUEUED → RUNNING → SUCCEEDED
                 → FAILED
                 → CANCELED
                 → SUPERSEDED
```

`SUPERSEDED`는 새 실행보다 늦게 deploy lock을 얻어 freshness 검사를 통과하지 못한 경우다. 이미 배포 중인 실행을 무조건 중단하는 상태가 아니다.

## 3. BuildArtifact

한 번 빌드되어 재사용되는 불변 산출물이다.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `artifactId` | string | component + commit + build number |
| `component` | enum | `ai`, `back`, `front`, `game-webgl`, `game-server` |
| `sourceCommit` | string | full SHA |
| `type` | enum | `container-image`, `webgl-bundle`, `test-report`, `build-log` |
| `storageMode` | enum | 초기 `local-docker`; 추후 `registry` 가능 |
| `imageRef` | string | 초기 `<component>:<full-commit-sha>`; credential 포함 금지 |
| `contentId` | string | `local-docker`에서는 Docker image ID, `registry`에서는 manifest digest인 `sha256:<64 hex>` |
| `createdAt` | date-time | UTC |
| `producerRunId` | string | PipelineRun 참조 |

초기 배포는 고유 `imageRef`를 사용하되 실행 직전 실제 image ID가 manifest의 `contentId`와 일치해야 한다. Unity Library/Temp/Logs는 BuildArtifact가 아니다.

## 4. DeploymentTarget

파트/환경 단위의 격리와 현재 상태를 나타낸다.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `targetId` | string | 예: `dev-back`, `integration-develop` |
| `environment` | enum | `dev`, `integration` |
| `scope` | enum | 파트 또는 `develop` |
| `composeProject` | string | target별 고유 |
| `lockResource` | string | target별 고유 |
| `currentReleaseId` | string? | 승격된 release |
| `knownGoodReleaseId` | string? | verify 완료 release |
| `releaseSequence` | integer | 감소 금지 |

## 5. Release

배포할 component local image ref/image ID의 원자적 묶음이다. 세부 wire format은 `contracts/release-manifest.schema.json`을 따른다.

주요 관계:

```text
PipelineRun 1 ── produces ── 0..1 Release
Release 1 ── contains ── 1..N BuildArtifact
DeploymentTarget 1 ── deploys ── N Release histories
```

상태 전이:

```text
CANDIDATE → DEPLOYING → VERIFYING → ACTIVE
                │           │
                └───────────┴→ FAILED_PENDING_DECISION
                                  ├→ ROLLING_BACK → ROLLED_BACK
                                  │                 └→ ROLLBACK_FAILED
                                  ├→ MANUAL_ACTION_REQUIRED
                                  └→ AWAITING_AI_RETRY_OR_APPROVAL
```

`ACTIVE` 전이는 통합 필수 검증과 정책 판정 후 current pointer를 한 번에 갱신할 때만 허용한다.

## 6. VerificationResult

배포 또는 rollback 뒤 수행한 검증 증거다. wire format은 `contracts/verification-result.schema.json`을 따른다.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `verificationId` | string | 유일 |
| `releaseId` / `targetId` | string | 필수 |
| `checks` | array | web, login, world, ai 또는 component readiness |
| `requiredNonAiPassed` | boolean | develop 판정에 필수 |
| `aiPassed` | boolean/null | AI 미대상일 때 null |
| `failureCode` | enum/null | 구조화된 분류 |
| `evidenceRefs` | string[] | secret-free log/report 경로 |
| `finalDecision` | enum | `PASS`, `ROLLBACK`, `MANUAL`, `AI_RETRY` |

## 7. RecoveryDecision

실패 분류와 조치를 연결한다.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `failureClass` | enum | `PRE_DEPLOY_FAILURE`, `REVERSIBLE_RUNTIME`, `DATA_OR_CONFIG_RISK`, `AI_EXTERNAL_ONLY`, `UNKNOWN` |
| `rollbackSafety` | enum | `SAFE`, `UNSAFE`, `UNASSESSED` |
| `dataChange` | enum | `none`, `reversible`, `irreversible` |
| `dbSchemaChanged` | boolean | 배포 전 확정 |
| `secretOrConfigChanged` | boolean | 값이 아닌 변경 여부만 |
| `decision` | enum | `NONE`, `AUTO_ROLLBACK`, `MANUAL`, `AI_RETRY` |
| `reason` | string | secret-free |
| `fromReleaseId` / `toReleaseId` | string? | rollback일 때 |
| `result` | enum | `NOT_RUN`, `SUCCEEDED`, `FAILED` |

`AUTO_ROLLBACK`은 `REVERSIBLE_RUNTIME && SAFE && dataChange != irreversible && !dbSchemaChanged && !secretOrConfigChanged`일 때만 유효하다.

## 8. SecretReference

값이 아닌 참조 메타데이터다.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `id` | string | 초기 Jenkins credential ID; 추후 AWS secret path alias 가능 |
| `purpose` | string | 사용 목적 |
| `scope` | string | job/environment 최소 범위 |
| `rotationOwner` | string | 담당 role |

금지 필드: value, token, password, privateKey, Unity session/license material, webhook URL 원문.

## 9. OperationalSignal

Alloy가 수집·정규화한 로그/metric 관측값이다.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `timestamp` | date-time | UTC |
| `source` | enum | `log`, `metric` |
| `environment`, `service`, `container`, `host` | string | bounded label |
| `body` / `value` | string/number | 수집 전 redaction 적용 |
| `labels` | object | userId/requestId/IP/email/token 등 고카디널리티·민감 값 금지 |

## 10. DetectionRule

Infra 담당자가 작성하고 승인해야 알림 가능한 규칙이다. wire format은 `contracts/detection-rule.schema.json`을 따른다.

수명 주기:

```text
DRAFT → APPROVED → ENABLED → DISABLED → RETIRED
          └───────────────→ 변경 시 새 version/DRAFT
```

Mattermost routing에는 `APPROVED + ENABLED + notify=mattermost + domain!=ci`가 모두 필요하다. 임시 규칙은 `expiresAt`이 지나면 알림할 수 없다.

## 11. AlertEvent

규칙 평가 결과와 전달 이력이다.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `eventId`, `fingerprint` | string | fingerprint가 dedupe/group 기준 |
| `ruleId`, `ruleVersion` | string | 원인 규칙 연결 |
| `status` | enum | `firing`, `resolved` |
| `severity`, `environment`, `service` | string | bounded |
| `summary`, `description` | string | sanitized; 원본 로그 전문 금지 |
| `startedAt`, `observedAt`, `resolvedAt` | date-time | UTC |
| `dashboardUrl`, `runbookUrl`, `silenceUrl` | uri | credential query 금지 |
| `deliveryStatus` | enum | `not-routed`, `pending`, `sent`, `failed`, `suppressed` |

동적 값은 rule label이 아닌 annotation/body에 둬 fingerprint 폭증을 방지한다.
