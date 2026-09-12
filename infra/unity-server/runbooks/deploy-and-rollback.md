# demo-game 배포·검증·복구 운영 절차

이 절차는 `demo-game`만 교체한다. Backend·AI·Web은 재시작 대상이 아니다. `infra/.env`는 공백 포함 검증 명령을 가질 수 있으므로 shell에서 `source`하지 말고 Jenkins credential binding 또는 필요한 환경 변수만 명시적으로 주입한다.

## 입력과 사전 확인

- game release manifest와 로컬 Docker image의 immutable ref/content ID가 일치한다.
- `GAME_ENV_FILE`, `CONNECTION_TOKEN_SECRET_FILE`, `GAME_DEPLOY_STATE_DIR`, `CI_ARTIFACT_DIR`가 주입되어 있다.
- 이전 정상 배포가 있으면 `known-good.json`이 존재한다.
- 단일 EC2이므로 EC2·Docker daemon·Nginx·Cloudflare origin 장애는 game-only rollback으로 복구할 수 없다.

## candidate 배포

```bash
export RELEASE_MANIFEST_PATH=/srv/festa/releases/release.json
export GAME_ENV_FILE=/srv/festa/infra/.env
export CONNECTION_TOKEN_SECRET_FILE=/srv/festa/secrets/connection-token.b64
export GAME_DEPLOY_STATE_DIR=/srv/festa/state/demo-game
export CI_ARTIFACT_DIR=/srv/festa/artifacts/demo-game

bash infra/unity-server/scripts/deploy-game.sh
```

`non-game-restarts-before.tsv`과 `non-game-restarts-after.tsv`가 다르면 승격하지 않는다.

## readiness 검증과 승격

실제 승인 접속을 수행한 실행기가 `PASS`만 담은 approval evidence 파일을 만든다. token·JTI·닉네임은 기록하지 않는다.

```bash
export APPROVAL_EVIDENCE_FILE=/srv/festa/artifacts/demo-game/approved-admission.txt
export ROOT_DOMAIN=ssafesta.world

bash infra/unity-server/scripts/game-readiness.sh
bash infra/unity-server/scripts/promote-game.sh
```

process running, loopback listener, 공개 WSS `101`, 승인 입장 네 조건이 모두 PASS일 때만 `current.json`과 `known-good.json`이 갱신된다.

## 실패 후보 복구

readiness 실패 원인을 아래 제한된 값 중 하나로 남긴 뒤 rollback을 한 번 실행한다.

```bash
export GAME_ROLLBACK_REASON=external_wss_failed
bash infra/unity-server/scripts/rollback-game.sh
```

허용 reason: `candidate_verification_failed`, `internal_listener_failed`, `external_wss_failed`, `approved_admission_failed`.

복구는 마지막 known-good image만 `--no-deps --wait demo-game`으로 되돌린다. 동일 candidate의 자동 재시도는 차단되며, rollback 자체가 실패하면 `failed/<releaseId>.json`과 Jenkins artifact를 첨부해 운영자가 조사한다.

## 증거 수집

```bash
export CLIENT_RELEASE_REF=<webgl-release-id-or-source-commit>
bash infra/unity-server/scripts/collect-deploy-evidence.sh
```

`deploy-evidence.json`에는 server/client ref, readiness 단계, 비대상 restart delta만 남긴다.
