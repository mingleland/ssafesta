# Implementation Plan: 변경 컴포넌트 CI와 dev 배포

**Branch**: `infra-001-ci-cd-pipelines` | **Date**: 2026-09-08 | **Spec**: [spec.md](./spec.md)

## Summary

고정 파트 브랜치 CI/CD를 폐기한다. `feature/* → develop` MR의 Front·Back build·test merge gate는 GitLab CI/Runner가 `rules:changes`로 수행한다. Jenkins는 MR을 다시 빌드하지 않고, Squash Merge된 `develop`에서 변경 컴포넌트를 빌드·검증한 뒤 dev에만 자동 배포한다. 여러 컴포넌트 변경은 전부 Jenkins CI 성공 후 하나의 dev 배포 묶음으로 적용하고, 중간 실패면 이미 갱신한 묶음 구성원을 직전 known-good 상태로 복구한다. demo는 develop push와 분리된 수동 promotion job으로, dev 검증을 통과한 release만 배포한다.

기존 `.gitlab-ci.yml`, `ci/` build adapter, Jenkins controller/agent, release manifest, image provenance, Compose 기반 dev deploy/verify 도구를 재사용한다. 새 구현은 GitLab MR gate, Jenkins develop 범위 판별, dev 배포 묶음 상태·rollback 경계에 한정한다.

## Technical Context

**Language/Version**: Jenkins Declarative Pipeline/Groovy, Bash, YAML/JSON Schema; Unity `6000.0.78f1`
**Primary Dependencies**: GitLab CI/Runner, Jenkins LTS + GitLab Branch Source, Docker Engine/Compose v2, existing Jenkins Lockable Resources and milestone steps
**Storage**: Jenkins artifacts/fingerprints; single EC2 local Docker image store; environment state files for dev current/known-good releases
**Testing**: shell unit tests for path detector; Jenkins/Groovy static checks; dev single- and multi-component deploy rehearsal; forced failure rollback rehearsal; manual demo promotion rehearsal
**Target Platform**: Ubuntu EC2, rootless build agent + rootful deploy agent, WebGL and Linux Dedicated Server
**Project Type**: Infrastructure as Code and Jenkins orchestration
**Performance Goals**: unrelated components never rebuild/deploy; stale run never overwrites newer dev state; one failed component never leaves a partial dev batch active
**Constraints**: GitLab MR runner has Docker access for Back Testcontainers; controller build prohibition; one Unity executor; secrets only through GitLab CI variables or Jenkins Credentials; no direct `develop` push; no demo automatic deploy; `festa-unity/Docker/` unchanged
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

GitLab CI owns merge-before Front·Back selection with native `rules:changes`. Jenkins owns merge-after `develop` selection with one repository-owned detector that emits a machine-readable record instead of using branch names.

| Changed path | GitLab MR gate | Jenkins CI/dev CD after `develop` merge |
|---|---|---|
| `festa-ai/**`, dev AI compose config | none (initial scope) | `ai` |
| `backend/**`, dev Back compose config | `back` | `back` |
| `festa-frontend/**`, dev Front compose config | `front` | `front` |
| `festa-unity/**`, dev Game compose config | none (initial scope) | `game` |
| Front·Back shared contract/CI paths | `front` + `back` | all CI, no dev deploy |
| Jenkins/deploy shared paths | no GitLab product gate | all CI, no dev deploy |
| documentation/spec-only paths | none | none |

GitLab's `rules:changes` evaluates the MR diff. A Jenkins `develop` Squash merge compares its pushed range (`before..after`; fallback `HEAD^..HEAD`). Invalid Jenkins range, unclassified runtime/deploy config, or detector error fails closed before Jenkins build or deployment.

### 2. Delivery topology

1. GitLab CI accepts same-project `feature/* → develop` only. It uses `rules:changes` to run required Front·Back build/test gates and GitLab blocks merge unless they pass. `jira-*` definitions remain disabled.
2. Jenkins `develop` push job detects the Squash merge range, runs all selected CI first, then creates one candidate dev batch only if every selected CI succeeds.
3. The dev batch transfers/verifies every candidate image before mutation. Under one batch lock it snapshots all affected component known-good state, deploys each selected component using existing service-scoped deploy/verify primitives, and promotes dev state only after all verify.
4. If a reversible deploy/verify failure occurs, restore each component already changed by this batch from the snapshot and verify restoration. DB/schema, secret/config, irreversible, unknown, or restoration failure preserves evidence and requires manual action.
5. Manual demo promotion selects an explicitly approved dev-verified full release manifest and reuses existing demo deploy → verify → promote/rollback logic. `develop` push never invokes it.

### 3. Freshness and state invariants

- A merge run rechecks that `develop` still points to its expected head before it obtains the dev batch lock. Superseded runs stop with no deploy.
- A batch has one `batchId`, one source range, ordered selected components, a before-state snapshot and candidate content IDs.
- The per-component dev current/known-good pointer changes only after its health verification; the batch promotion marker changes only after every selected component succeeds.
- Rollback may use only the batch snapshot, not an arbitrary old manifest. This avoids weakening normal commit freshness checks.
- Shared CI paths never deploy because no runtime component changed. Component runtime/deploy config counts as that component change.

### 4. Existing code to extend

```text
.gitlab-ci.yml                                 Front·Back MR merge gate; jira-* definitions remain disabled
Jenkinsfile                                   develop dispatcher and detector entry
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
├── pipelines/{component,develop,demo-promotion}.groovy
├── scripts/{detect-changed-components,deploy-dev-batch}.sh
└── tests/{detect-changed-components,deploy-dev-batch}.*
infra/environments/
├── compose/dev/{base,ai,back,front,game}.yaml
└── state/dev/                            # runtime only, never committed
specs/infra-001-ci-cd-pipelines/
├── {spec,plan,research,data-model,quickstart}.md
└── contracts/{component-pipeline,changed-component}.md
```

## Delivery Order

1. Add GitLab `rules:changes` Front·Back gate and runner/merge-setting rehearsal.
2. Add Jenkins develop path detector and dispatch only supported develop pushes.
3. Refactor merge-after component CI to accept `CI_COMPONENT` independently of source branch; keep it deploy-free.
4. Build dev batch coordinator using existing component deploy/verify commands; add snapshot/promotion/rollback tests.
5. Wire `develop` push to selected CI then dev batch; rehearse single component, shared-only, multi-component and rollback paths on dev.
6. Separate demo into manual promotion; rehearse approved manifest deployment and reject non-dev-verified selection.

## Post-Design Constitution Check

All changed decisions conform to amended Article II-10. No unresolved implementation choice blocks tasks: shared path handling, failure semantics, batch rollback boundary and demo approval are specified by [research.md](./research.md), [data-model.md](./data-model.md), and [contracts/changed-component-contract.md](./contracts/changed-component-contract.md).
