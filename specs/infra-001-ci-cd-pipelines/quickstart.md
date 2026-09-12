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

## 7. QA 완료 WebGL Package 자동 배포

1. Jenkins Credentials에 Username/Password 타입 `gitlab-package-read`를 만든다. GitLab Deploy Token은 대상 project의 `read_package_registry`만 허용한다. controller 환경의 `GITLAB_PACKAGE_READ_CREDENTIAL_ID`에는 이 ID만 저장한다.
2. Job DSL `infra/jenkins/jobs/gitlab-webgl-package-deploy.groovy`를 적용하고 `festa-webgl-package-deploy` job이 `develop`의 `infra/jenkins/pipelines/webgl-package-deploy.groovy`를 읽는지 확인한다.
3. `demo.conf.template`을 운영 Nginx에 반영해 `nginx -t` 후 reload하고 `.br`·`.unityweb` Brotli 규칙을 확인한다. deploy-agent도 재빌드·재생성해 호스트 `${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}`이 컨테이너 `/srv/festa/webgl`에 bind됐는지 확인한다. `WEBGL_PUBLIC_BASE_URL`은 기본 `https://demo.ssafesta.world/unity`다.
4. Unity 담당자 PC에는 Jenkins Agent가 필요 없다. GitLab package write token과 해당 job만 Build 가능한 Jenkins service account/API token을 환경변수로 넣고 다음을 실행한다.

```bash
GITLAB_PACKAGE_TOKEN='<write token>' \
JENKINS_URL='https://ci.ssafesta.world' \
JENKINS_USER='<webgl publisher service account>' \
JENKINS_API_TOKEN='<api token>' \
infra/jenkins/scripts/publish-webgl-release.sh \
  festa-webgl-release-490bde34.zip 490bde34
```

5. zip과 `.sha256` 업로드가 모두 성공한 뒤 Jenkins가 `RELEASE_ID=490bde34`, `ARTIFACT_SHA256=<64 hex>`로 시작하는지 확인한다. 토큰 원문을 console·artifact에 남기지 않는다.
6. 성공 후 EC2에서 `readlink /srv/festa/webgl/current`, `readlink /srv/festa/webgl/previous`와 각 release의 `.artifact-sha256`을 확인한다. `find /srv/festa/webgl -maxdepth 1 -name 'current.legacy.[0-9]*'`로 본 UTC 14자리 timestamp legacy는 최신 두 개만 남아야 하며, timestamp 형식이 아닌 이름은 정리 대상이 아니다. 검증·출처 검사 실패 실행 뒤에는 legacy 목록이 변하면 안 된다.
7. 공개 검증은 다음 기준을 모두 만족해야 한다: `/manifest.json` JSON + `no-cache`, `/index.html` HTML + `no-cache`, manifest의 Build 4종 HTTP 200, 확장자별 MIME, 압축 파일 `Content-Encoding: br`, Build 파일 `public, max-age=31536000, immutable`.
8. 공개 검증을 의도적으로 실패시킨 rehearsal에서는 job이 실패하고 `current`가 실행 전 release로 복원되어야 한다. Dedicated Server 컨테이너 ID와 시작 시각은 바뀌지 않아야 한다.

로컬 계약 검증:

```bash
infra/jenkins/tests/deploy-webgl-release.sh
infra/jenkins/tests/test-foundation.sh
```
