# Quickstart: 변경 컴포넌트 CI와 dev 배포 검증

Reference: [changed-component contract](./contracts/changed-component-contract.md), [data model](./data-model.md).

## Prerequisites

- GitLab Runner is assigned to the project; its Back job has Docker executor or Docker socket access.
- GitLab project permits merge only when required pipeline succeeds.
- Jenkins controller, build/deploy/Unity agents and existing credentials are healthy.
- GitLab webhook delivers `develop` Push events to Jenkins.
- Dev compose targets and their current known-good state exist before rollback rehearsal.

## 1. GitLab MR merge gate

1. Create `feature/S15P21A604-N-...` from current `develop`; change one file under `backend/`.
2. Open same-project MR to `develop`.
3. Expected: GitLab `back-test` and `back-build` pass; no Jenkins job or `dev-back` container is recreated.
4. Repeat with `festa-frontend/**`; expect only Front jobs. Change a Front·Back shared contract path; expect both gates.

## 2. Required gate failure

1. Make either required GitLab build/test job fail.
2. Expected: GitLab prevents merge to `develop`; no Jenkins dev deployment starts.

## 3. Squash merge to dev

1. Squash merge a successful MR.
2. Expected: develop push identifies the Squash range, runs selected CI, deploys/verifies only matching dev target, records immutable content ID and active dev batch.
3. Confirm unrelated dev container IDs remain unchanged.

## 4. Multi-component atomicity

1. Create MR changing `backend/**` and `festa-frontend/**`; make both CI succeed.
2. Merge it. Expected: both candidate artifacts are ready before either dev target mutates.
3. Force the second component verification to fail in dev rehearsal.
4. Expected: first changed component is restored from this batch's before-state; batch is `ROLLED_BACK` or `MANUAL_ACTION_REQUIRED` if restoration cannot verify; no batch is marked active.

## 5. Stale run

1. Queue two develop runs where the older run reaches the deploy lock last.
2. Expected: older run is `SUPERSEDED`; it makes no deployment or state-pointer change.

## 6. Demo promotion

1. Select an active dev-verified release in the manual Jenkins demo promotion job.
2. Expected: demo deploy/verify/promotion evidence is archived.
3. Select a non-active or partial dev batch. Expected: job rejects it before demo mutation.
