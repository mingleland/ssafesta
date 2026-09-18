# Implementation Plan: 변경 컴포넌트 CI와 dev 배포

**Branch**: `infra-001-ci-cd-pipelines` | **Date**: 2026-09-08 | **Spec**: [spec.md](./spec.md)

## Summary

고정 파트 브랜치 CI/CD를 폐기한다. `feature/* → develop` MR의 Front·Back build·test merge gate는 GitLab CI/Runner가 `rules:changes`로 수행한다. Game 변경 MR은 Jenkins Unity agent가 MR head SHA에서 `ci/test`만 실행해 GitLab 필수 상태를 게시하며, `ci/test`의 Unity 스크립트 컴파일·EditMode 정적 검사를 통과해야 한다. 이 경로는 어떤 배포도 하지 않는다.

실제 팀 운영 현실(demo 중심 검증)에 맞춰 별도의 `dev.ssafesta.world` 환경은 운영하지 않으며, `demo.ssafesta.world` 를 `develop` 기준 단일 활성 통합 환경(Staging)으로 확정한다. `develop` 에 머지된 변경은 Jenkins develop 파이프라인을 통해 컴포넌트별 필수 검증 후 `demo.ssafesta.world` 에 자동 배포된다. Demo 릴리스는 `candidate`(자동 검증 중) → `current`(자동 readiness 성공, 실제 demo 실행 중) → `known-good`(사람의 실제 서비스 검증 성공) 3단계로 엄격히 분리 관리한다.

수동 승격(Production Promotion)은 가상의 dev→demo가 아니라 **`develop → main` 승격 정책**으로 전환하며, `main` 브랜치는 `https://ssafesta.world` 에서 서비스되는 실제 사용자 프로덕션(Production) 운영 환경을 나타낸다. 팀이 `demo.ssafesta.world` 에서 충분히 검증하여 known-good 으로 승인한 릴리스만 `main` 및 프로덕션으로 승격하며, **Architecture Invariant로 develop → main 승격 MR은 절대 Squash Merge를 금지(`squash=false` 강제, develop 계보 보존)**하고, demo에서 검증된 동일 아티팩트를 재빌드 없이 재사용한다(`Demo Artifact == Production Artifact`).

기존 `.gitlab-ci.yml`, `ci/` build adapter, Jenkins controller/agent, release manifest, image provenance, Compose 기반 deploy/verify 도구를 재사용한다. WebGL은 Unity 담당자가 전달한 릴리스 zip 패키지 또는 Package Registry 업로드 산출물을 받아 배포하며, `deploy-game.sh` 의 프리팹 트리 대조 가드로 클라이언트-서버 정합성을 보장한다. Dedicated Server 배포는 스모크 러너 의존성을 제거하고 1~3단계(프로세스, 내부 포트, WSS 101) 통과로 current 승격을 확정한다. Redis는 40명 실측(1.27MB) 기반 `mem_limit: 256m` 상한 및 `appendonly yes` AOF 영속성을 적용한다.

## Technical Context

**Language/Version**: Jenkins Declarative Pipeline/Groovy, Bash, YAML/JSON Schema; Unity `6000.0.78f1`
**Primary Dependencies**: GitLab CI/Runner, Jenkins LTS + GitLab Branch Source, Docker Engine/Compose v2, existing Jenkins Lockable Resources and milestone steps
**Storage**: Jenkins artifacts/fingerprints; GitLab Generic Package Registry; single EC2 local Docker image store; `/srv/festa/webgl/releases`와 current/previous symbolic links
**Testing**: shell unit tests for path detector and Unity MR test-only pipeline; Jenkins/Groovy static checks; `ci/test` success·failure MR rehearsal with no deployment; dev single- and multi-component deploy rehearsal; forced failure rollback rehearsal; manual demo promotion rehearsal
**Target Platform**: Ubuntu EC2, rootless build agent + rootful deploy agent, WebGL and Linux Dedicated Server
**Project Type**: Infrastructure as Code and Jenkins orchestration
**Performance Goals**: unrelated components never rebuild/deploy; stale run never overwrites newer dev state; one failed component never leaves a partial dev batch active
**Constraints**: GitLab MR runner has Docker access for Back Testcontainers; controller build prohibition; one Unity executor; secrets only through GitLab CI variables or Jenkins Credentials; no direct `develop` push; QA 완료 Package upload만 WebGL 자동 배포 승인; 일반 develop push는 demo 자동 배포 금지; `festa-unity/Docker/` unchanged
**Scale/Scope**: components `ai`, `back`, `front`, `game`; same-project `feature/*` MR to `develop`; changed source, component deploy config, shared CI paths

## Constitution Check

| Gate | Design response | Result |
|---|---|:---:|
| Article II-7 container deployment | Existing immutable image ref/content ID and deploy verification remain required. | PASS |
| Article II-8 endpoint | No endpoint is added to Jenkins pipeline; environment runtime config remains source of endpoint values. | PASS |
| Article II-10 branch/CI-CD | `feature/*` MR runs changed CI; Squash-merged `develop` deploys changed dev components; demo is approved promotion only. | PASS |
| Article III secret handling | Detector and state records store only paths, SHAs, component names and credential IDs; no secret values. | PASS |
| Article IV traceability | Every run records source range, selected components, candidate content IDs, deployment/verification/rollback result. | PASS |

## Architecture

### 1. Change selection

GitLab CI owns merge-before Front·Back selection with native `rules:changes`. Jenkins Unity agent owns merge-before Game selection and test-only validation of the MR head SHA. Jenkins develop job owns merge-after `develop` selection with one repository-owned detector that emits a machine-readable record instead of using branch names.

| Changed path | GitLab MR gate | Jenkins CI/dev CD after `develop` merge |
|---|---|---|
| `festa-ai/**`, dev AI compose config | none (initial scope) | `ai` |
| `backend/**`, dev Back compose config | `back` | `back` |
| `festa-frontend/**`, dev Front compose config | `front` | `front` |
| `festa-unity/**`, Game CI adapter, Unity project settings | Jenkins Unity test-only (`ci/test`) | `game` |
| Front·Back shared contract/CI paths | `front` + `back` | all CI, no dev deploy |
| Jenkins/deploy shared paths | no GitLab product gate | all CI, no dev deploy |
| documentation/spec-only paths | none | none |

GitLab's `rules:changes` evaluates the MR diff. A Jenkins `develop` Squash merge compares its pushed range (`before..after`; fallback `HEAD^..HEAD`). Invalid Jenkins range, unclassified runtime/deploy config, or detector error fails closed before Jenkins build or deployment.

### 2. Delivery topology

1. GitLab CI accepts same-project `feature/* → develop` only. It uses `rules:changes` to run required Front·Back build/test gates; Game changes dispatch a Jenkins Unity test-only job for the MR head SHA. GitLab blocks merge unless every applicable status passes. `jira-*` definitions remain disabled.
2. The Unity MR job calls only `ci/test`; it records the head SHA and test report, publishes success/failure to the matching GitLab MR commit, and has no deploy credential, Compose mutation, image package or promotion stage.
3. Jenkins `develop` push job detects the merge range, runs all selected CI first, then creates one candidate batch for `demo.ssafesta.world` only if every selected CI succeeds.
4. The demo batch transfers/verifies candidate images before mutation. Under batch lock it deploys selected components to `demo.ssafesta.world`, runs service-scoped readiness, and transitions the candidate to `current` only after all readiness checks pass.
5. If an automated readiness failure occurs, candidate deployment is rolled back to previous state. After human verification of actual user journeys on `demo.ssafesta.world`, the release is explicitly approved as `known-good`.
6. Production Promotion (`develop → main`) selects an explicitly human-approved `known-good` release, fast-forwards/merges to `main` without squash (`squash=false`), and promotes the identical verified artifacts to `ssafesta.world` without rebuilding.

### 3. Freshness and state invariants

- A merge run rechecks that `develop` still points to its expected head before it obtains the batch lock. Superseded runs stop with no deploy.
- Demo release lifecycle strictly adheres to 3 states: `candidate` (readiness in progress) → `current` (readiness passed, live on demo, `current ≠ known-good`) → `known-good` (human verified on demo).
- Rollback uses the last human-verified `known-good` snapshot, never an unverified intermediate `current`.
- **Architecture Invariant**: `develop → main` promotion MR MUST NOT squash (`squash=false` enforced, preserving develop ancestry).
- **Artifact Invariant**: `Demo Artifact == Production Artifact` (no rebuild during production promotion).
- Shared CI paths never deploy because no runtime component changed. Component runtime/deploy config counts as that component change.
- Redis container enforces `mem_limit: 256m` based on 40-user peak empirical observation (1.27MB) and `appendonly yes` to safeguard login sessions across container restarts.

### 4. WebGL Package Registry delivery

1. Unity 담당자 PC의 `publish-webgl-release.sh`가 zip 구조를 확인하고 SHA-256을 계산한다.
2. 도우미가 `festa-webgl/<release-id>/festa-webgl-release-<release-id>.zip`과 checksum을 업로드한다. 두 업로드 성공 뒤에만 Jenkins job을 호출한다.
3. deploy-agent는 Jenkins의 GitLab Deploy Token으로 package를 내려받고 SHA-256·안전한 ZIP entry·manifest 참조를 검증한다.
4. 검증된 산출물을 `/srv/festa/webgl/releases/<release-id>`에 설치하고 `current`를 원자적으로 전환한다.
5. 공개 HTTP의 MIME·Brotli·Cache-Control 검증 실패 시 이전 `current`를 복원한다. known-good 기록까지 성공했을 때만 `current`·`previous`와 최신 `current.legacy.<UTC 14자리 timestamp>` 두 개를 남기고 오래된 legacy를 정리한다. 이 경로는 Dedicated Server를 조작하지 않는다.

### 5. Existing code to extend

```text
.gitlab-ci.yml                                 Front·Back MR merge gate and Game-change Jenkins dispatch/status contract
Jenkinsfile                                   develop dispatcher and detector entry
infra/jenkins/jobs/gitlab-unity-mr-validation.groovy  Game MR test-only Jenkins job
infra/jenkins/pipelines/unity-mr-validation.groovy    checkout SHA → validate → EditMode → status, no deploy
infra/jenkins/pipelines/component.groovy      merge-after component CI stages, split from direct dev deploy
infra/jenkins/pipelines/develop.groovy        replace all-component/demo flow with selected dev batch
infra/jenkins/scripts/deploy-dev-component.sh existing one-component deploy primitive
infra/environments/scripts/deploy-environment.sh / verify-environment.sh
                                               existing service apply/verification primitives
infra/deploy/scripts/build-release-manifest.sh existing immutable component manifest creator
infra/jenkins/scripts/freshness.sh            keep normal freshness validation
```

New scripts are justified only for one shared path detector and one dev batch coordinator/state format. Do not duplicate each component's build or Compose logic.

## Project Structure

```text
infra/jenkins/
├── jobs/gitlab-webgl-package-deploy.groovy
├── pipelines/{component,develop,demo-promotion,webgl-package-deploy}.groovy
├── scripts/{detect-changed-components,deploy-dev-batch,deploy-webgl-release,publish-webgl-release}.sh
└── tests/{detect-changed-components,deploy-dev-batch,deploy-webgl-release}.*
infra/environments/
├── compose/dev/{base,ai,back,front,game}.yaml
└── state/dev/                            # runtime only, never committed
specs/infra-001-ci-cd-pipelines/
├── {spec,plan,research,data-model,quickstart}.md
└── contracts/{component-pipeline,changed-component}.md
```

## Delivery Order

1. Add GitLab `rules:changes` Front·Back gate and runner/merge-setting rehearsal.
2. Add Jenkins Unity MR test-only validation and required-status rehearsal before any develop deploy work.
3. Add Jenkins develop path detector and dispatch only supported develop pushes.
4. Refactor merge-after component CI to accept `CI_COMPONENT` independently of source branch; keep it deploy-free.
5. Build dev batch coordinator using existing component deploy/verify commands; add snapshot/promotion/rollback tests.
6. Wire `develop` push to selected CI then dev batch; rehearse single component, shared-only, multi-component and rollback paths on dev.
7. Separate demo into manual promotion; rehearse approved manifest deployment and reject non-dev-verified selection.

## Post-Design Constitution Check

All changed decisions conform to amended Article II-10. No unresolved implementation choice blocks tasks: shared path handling, failure semantics, batch rollback boundary and demo approval are specified by [research.md](./research.md), [data-model.md](./data-model.md), and [contracts/changed-component-contract.md](./contracts/changed-component-contract.md).
