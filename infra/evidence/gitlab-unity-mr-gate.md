# GitLab Unity MR gate 실측

Jira: `S15P21A604-185` / Task: `T048`, `T050`

`festa-unity/**`·Game CI adapter·Unity project 설정을 바꾼 MR 은 Jenkins Unity agent 에서
`ci/test` (Unity 스크립트 컴파일 + EditMode 정적 검사) 만 실행하고, 그 결과가 GitLab MR 의
필수 성공 상태가 된다. 이 경로는 이미지 build·package·Registry 업로드·배포·컨테이너 재시작을
하지 않는다 (FR-003b, FR-003c).

## 결선 (T048, 완료)

| 항목 | 값 |
|---|---|
| dispatch job | `.gitlab-ci.yml` `unity-mr-validation-dispatch` |
| 감지 경로 | `festa-unity/**/*`, `infra/unity-server/**/*` |
| Jenkins job | `festa-unity-mr-validation` |
| Jenkins pipeline | `infra/jenkins/pipelines/unity-mr-validation.groovy` |
| 상태 게시 | Jenkins → GitLab commit status (MR head SHA) |

정적 계약은 `infra/jenkins/tests/test-foundation.sh` 와 `infra/jenkins/tests/unity-mr-validation.sh`
가 검사한다 — Unity agent label, no-deploy 명령 집합, commit status context, credential masking.

## 실측 (T050)

아래 3개 시나리오를 각각 실제 MR 로 돌리고 결과를 채운다. 세 경우 모두 dev/demo 컨테이너의
restart delta 가 0 이어야 한다 — Unity MR gate 는 아무것도 배포하지 않기 때문이다.

### 수집 명령

```bash
# MR 파이프라인 시작 전과 종료 후 각각 실행해 두 출력을 비교한다.
sudo docker ps -a --format '{{.Names}} {{.RestartCount}} {{.Image}}' | sort
DOCKER_HOST=unix:///run/user/1000/docker.sock \
  docker ps -a --format '{{.Names}} {{.RestartCount}} {{.Image}}' | sort
```

### 1. 정상 EditMode — merge 허용

| 항목 | 값 |
|---|---|
| MR | *(작성)* |
| head SHA | *(작성)* |
| GitLab pipeline | *(작성)* |
| Jenkins build | *(작성)* |
| Unity commit status | *(작성: success)* |
| merge 가능 여부 | *(작성)* |
| 컨테이너 restart delta | *(작성: 0 이어야 한다)* |

### 2. 의도적 컴파일 / EditMode 실패 — merge 차단

| 항목 | 값 |
|---|---|
| MR | *(작성)* |
| 주입한 결함 | *(작성: 예 — 존재하지 않는 심볼 참조)* |
| Jenkins build | *(작성)* |
| Unity commit status | *(작성: failed)* |
| GitLab merge 차단 확인 | *(작성)* |
| 컨테이너 restart delta | *(작성: 0 이어야 한다)* |

### 3. Unity agent 미가용 — merge 차단

Jenkins 에서 `festa-jenkins-agent-unity` 노드를 offline 으로 표시한 뒤 MR 을 갱신한다.
agent 부재는 성공이나 skip 이 아니라 **실패** 여야 한다 (FR-003b).

| 항목 | 값 |
|---|---|
| MR | *(작성)* |
| agent 상태 | *(작성: offline)* |
| dispatch 결과 | *(작성)* |
| Unity commit status | *(작성: failed)* |
| GitLab merge 차단 확인 | *(작성)* |
| 컨테이너 restart delta | *(작성: 0 이어야 한다)* |

## 판정

세 시나리오가 모두 기대대로면 T050 을 완료로 표시한다. 하나라도 어긋나면 그 항목을 여기에
남기고 `docs/25_트러블슈팅.md` 로 연결한다 — 통과한 것만 적으면 다음 사람이 같은 함정을 다시 밟는다.
