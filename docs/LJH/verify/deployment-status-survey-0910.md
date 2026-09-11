# 배포 현황 전수조사 — 파트 × 환경 × 단계

`S15P21A604-596` · 2026-09-10

---

## 0. 요약

**자동화된 것은 `develop` 병합 → dev 환경의 AI·BE·FE 셋뿐이다.** 나머지는 전부 사람이 시작한다.

```
dev AI·BE·FE        develop push -> Jenkins -> deploy-dev-batch    무인 자동
dev Game 서버       같은 병합 경로에서 제외돼 있다                  develop.groovy:32
Game WebGL          Unity Editor 빌드(사람) -> 스크립트 1회 실행    코드는 완성, 실환경 wire 0
demo / prod         자동 배포 경로가 코드에 없다                    deploy-environment.sh:27
```

**가장 중요한 발견 둘.**

1. **Game WebGL 배포는 `-581` 로 코드가 다 들어왔지만 아직 한 번도 못 돈다.** `infra/evidence/webgl-package-deploy-preflight.md` 의 9개 항목이 **전부 미체크** — Deploy Token 미발급, Jenkins credential 미등록, Job DSL 미적용, deploy-agent 재빌드 미확인, nginx reload 미적용. 즉 **등급은 자동인데 상태는 미구축**이다.
2. **`develop.groovy:142` 가 아직 `PUBLIC_API_BASE_URL=/__dev/api` 를 주입한다.** `#163`(09-10 14:53)에서 인프라가 root `/api` + Dev HTTPS 로 확정했는데 **배포 파이프라인에는 반영 전**이다. `public-entry-contract.md:11` 도 옛 계약(`http://${EC2_PUBLIC_IP}/__dev/...`) 그대로다.

---

## 1. 기준과 원칙

```
기준 SHA   origin/develop  036211c44a6d13079bb42a03bdea8dad0eae2548  (09-10 17:14)
```

판정 근거는 **저장소 코드 · Jenkins job/pipeline 정의 · GitLab API 실측**이다. 문서·Jira·spec 체크박스는
참고만 했다 — 이 조사의 계기 자체가 *"내가 쓴 문서가 36분 만에 낡았다"* 였다(§7).

`스크립트 존재 ≠ pipeline 호출 ≠ 무인 자동화` 를 끝까지 갈랐다. 근거를 못 잡은 칸은 **확인 불가**로 남겼다.

**Jenkins 실행 이력은 확인 불가다** — Jenkins 는 EC2 내부(`ci.ssafesta.world`)이고 이 세션에 접근 수단이
없다. 그래서 "돌았는가" 는 GitLab 측 흔적(Package Registry 업로드 시각·pipeline)으로만 간접 확인했다.

---

## 2. 격자표 — 등급 / 상태

`build` = 산출물 생성 · `publish` = 전달(레지스트리·전송) · `deploy` = 전개·전환.
**Local 의 `deploy` 는 배포가 아니라 로컬 bring-up(실행)으로 정의한다.**

### FE (React)

| 환경 | build | publish | deploy |
|---|---|---|---|
| Local | 수동 / 정상 — `npm run dev`·`npm run build` | 없음 | 수동 / 정상 — vite dev server |
| Dev | **자동 / 정상** `ci/build:9` | **자동 / 정상** `transfer-local-images.sh --export` | **자동 / 정상** `deploy-dev-batch.sh` |
| Demo | 확인 불가 | 확인 불가 | **없음 / 미구축** `deploy-environment.sh:27` |
| Prod | 없음 / 미구축 | 없음 / 미구축 | 없음 / 미구축 |

### BE (Spring)

| 환경 | build | publish | deploy |
|---|---|---|---|
| Local | 수동 / 정상 | 없음 | 수동 / 정상 — compose |
| Dev | **자동 / 정상** `ci/build:8` | **자동 / 정상** | **자동 / 실패 이력 있음** — `#160` PostgreSQL credential drift 로 첫 배포 중단 |
| Demo | 확인 불가 | 확인 불가 | **없음 / 미구축** |
| Prod | 없음 / 미구축 | 없음 / 미구축 | 없음 / 미구축 |

### AI (FastAPI)

| 환경 | build | publish | deploy |
|---|---|---|---|
| Local | 수동 / 정상 — 레시피 `-548`, `festa-ai/README.md` | 없음 | 수동 / 정상 |
| Dev | **자동 / 정상** `ci/build:7` | **자동 / 정상** | **자동 / 실패 이력 있음** — `-555` healthcheck 경로 불일치(진행 중) |
| Demo | 확인 불가 | 확인 불가 | **없음 / 미구축** |
| Prod | 없음 / 미구축 | 없음 / 미구축 | 없음 / 미구축 |

**MR 게이트 주의** — AI 는 `.gitlab-ci.yml` 에 **아직 job 이 없다**. `ai-test`·`ai-build` 가 오늘 실행된 것은
열린 MR(`!588`·`!605`·`!607`, `-155`)의 브랜치가 `.gitlab-ci.yml` 을 바꿨기 때문이다. develop 기준으로는
`front-*`·`back-*`·`mr-status` 셋뿐이다.

### Game — WebGL

| 환경 | build | publish | deploy |
|---|---|---|---|
| Local | 수동 / 정상 — Unity Editor | 수동 / 정상 — `share/` 정적 서버 | 수동 / 정상 |
| Dev | **없음** — CI 에서 **의도적으로 제외**(`7095ca1e`) | — | — |
| Demo | **수동 / 정상** — Unity 담당자 PC Editor 빌드 | **반자동 / 미구축** `publish-webgl-release.sh` | **자동 / 미구축** `festa-webgl-package-deploy` |
| Prod | 없음 / 미구축 | 없음 / 미구축 | 없음 / 미구축 |

**"자동인데 미구축"** 의 뜻 — 코드·job·테스트가 develop 에 다 있는데 실환경 credential·job 등록·nginx
반영이 아직 없어 **한 번도 못 돌았다**(§4-2).

### Game — Dedicated Server

| 환경 | build | publish | deploy |
|---|---|---|---|
| Local | 수동 / 정상 | 없음 | 수동 / 정상 |
| Dev | **자동 / 확인 불가** `unity.groovy` | **자동 / 확인 불가** `ci/package` | **자동 / 확인 불가** `deploy-component.sh` |
| Demo | 확인 불가 | 확인 불가 | 확인 불가 — infra-003 범위 |
| Prod | 없음 / 미구축 | 없음 / 미구축 | 없음 / 미구축 |

**확인 불가 사유** — `unity.groovy` 가 develop·linux-server·WebGL 을 모두 도는 파이프라인인데
`infra/jenkins/jobs/*.groovy` 5개 중 **어느 job 도 `unity.groovy` 를 scriptPath 로 지정하지 않는다**(전부
`Jenkinsfile`). 그리고 `Jenkinsfile:21` 은 `develop` 이 아닌 브랜치에서 **에러로 끝난다**. 이 파이프라인이
지금 무엇에 의해 실행되는지 저장소 근거로는 잡히지 않는다.

---

## 3. 파트별 실제 호출 흐름

### 3-1. dev AI·BE·FE — 유일한 무인 자동 경로

```
develop push
└ Jenkinsfile:22            load('infra/jenkins/pipelines/develop.groovy').call()
  ├ develop.groovy:11       detect-changed-components.sh  -> selection.json
  ├ develop.groovy:36-42    component.groovy.call(component)  (컴포넌트마다)
  │   └ component.groovy:19  ci/validate -> ci/test -> ci/build -> ci/package
  ├ develop.groovy:70       build-release-manifest.sh      -> release-manifest.json
  ├ develop.groovy:71       transfer-local-images.sh --export  -> /var/lib/festa-image-transfer
  ├ develop.groovy:77-85    node('deploy') 에서 --import      (Deploy Candidate Receipt)
  └ develop.groovy:92-146   node('deploy') deploy-dev-batch.sh
      └ deploy-environment.sh --environment dev --component <c>
          -> compose up -d --no-deps -> verify-environment.sh -> promote/rollback
```

freshness 검사가 있어 **새 develop head 가 있으면 배포하지 않고 `NOT_BUILT`(exit 75)** 로 끝난다
(`develop.groovy:126`). 롤백은 배치 스냅샷으로만 한다(`deploy-environment.sh:52-55`).

### 3-2. dev Game — 같은 경로에서 잘린다

```
develop.groovy:32   deployComponents.findAll { it in ['ai','back','front'] }
develop.groovy:87   NO_OP: game deployment remains on the Phase 3 WebGL path;
                    Dedicated Server deployment is infra-003
```

game 은 `component.groovy` 까지는 돈다(`unity-6000.0.78f1` 노드, `ci/validate|test|build|package`).
다만 `ci/build:10` 이 **`--target linux-server` 만** 부른다 — WebGL 은 여기서 안 만든다.

```
component.groovy:31-33   game 은 image-metadata.json 만 stash 한다
                         "WebGL/Linux Server outputs stay on the Unity Agent"
```

### 3-3. Game WebGL — 사람이 시작하지만 그 뒤는 한 줄로 이어진다

```
① Unity Editor 로 WebGL 빌드           사람 (CI 에서 제외됨 — 7095ca1e)
② publish-webgl-release.sh <zip> <id>  사람이 1회 실행. 그 안에서:
     zip 구조 검증(index.html·manifest.json·Build/·TemplateData/)
     sha256 계산
     PUT  .../packages/generic/festa-webgl/<id>/festa-webgl-release-<id>.zip
     PUT  같은 경로에 .sha256
     POST <jenkins>/job/festa-webgl-package-deploy/buildWithParameters
          RELEASE_ID=<id>  ARTIFACT_SHA256=<sha>      <- job 을 스스로 트리거한다
③ festa-webgl-package-deploy (pipelineJob, stringParam 2개, trigger 블록 없음)
     └ webgl-package-deploy.groovy:31  deploy-webgl-release.sh
         Registry 다운로드 -> SHA 대조 -> 안전한 ZIP 검증 -> manifest 확인
         -> /srv/festa/webgl/releases/<id> 설치 -> current 원자적 전환
         -> 공개 HTTP MIME·Brotli 검증 실패 시 이전 current 복원
```

**따라서 ②와 ③은 사람 개입 1회로 이어진다.** job 자체는 파라미터를 받는 수동 job 이지만, 실제로는
`publish-webgl-release.sh` 가 그 파라미터를 채워 호출한다 — **사람이 Jenkins UI 에 값을 넣는 구조가 아니다.**

기존 Package Registry 산출물 8건의 파일명이 이미 `festa-webgl-release-<sha>.zip` 이라(§4-3) **이름 규약
불일치는 없다.**

### 3-4. GitLab CI — 배포 아님, MR 게이트 전용

```
.gitlab-ci.yml:27-34   stages: validate · test · build · sync
                       # - deploy   주석 처리됨
job                    mr-status · front-test · front-build · back-test · back-build
                       jira-* 3종은 workflow 에서 when: never
```

**GitLab CI 는 어떤 환경에도 배포하지 않는다.** 배포는 전부 Jenkins 쪽이다.

---

## 4. 지금 사람이 하는 일

### 4-1. Unity WebGL 릴리스 (가장 큰 수동 비용)

| 단계 | 주체 | 측정값 |
|---|---|---|
| Unity Editor WebGL 빌드 | Unity 담당자 PC | 미측정 (`#142` 회신 기준 18~30분대 언급, 로그 근거 없음) |
| `publish-webgl-release.sh` 실행 | 사람 1회 | 미측정 |
| 결과 공유(`#142` 코멘트) | 사람 | 미측정 |

**Package Registry 실측** (GitLab API, `festa-webgl` 8건)

```
107  490bde34  09-10 09:13     156,437,798 B
106  6e69c0b6  09-09 17:57
105  e6e60774  09-09 14:30     153,797,201 B   <- 현재 로컬 검증 기준본
104  664bc74e  09-09 11:16                     <- #142 에서 폐기 요청됨(셰이더 결함)
103  d1165eb3  09-09 09:46                     <- 같은 계열 결함
100  e30d8d49  09-08 15:52
 96  e4ea8c26  09-08 10:02
 88  41107ee2  09-07 14:42
```

**09-09 하루에 4건**(103·104·105·106) 이 올라갔고 그중 **2건(103·104)이 폐기 요청**됐다. `-581` 이 들어온
15:02 이후 **새 업로드는 아직 없다**(최신이 09-10 09:13).

### 4-2. WebGL 자동 배포를 막고 있는 것 — preflight 9건 전부 미체크

`infra/evidence/webgl-package-deploy-preflight.md` 원문 그대로다.

```
[ ] read_package_registry 전용 Deploy Token 발급
[ ] Jenkins credential 'gitlab-package-read' 등록·masking 확인
[ ] festa-webgl-package-deploy Job DSL 적용, publisher 계정 권한 확인
[ ] Unity 담당자 PC -> ci.ssafesta.world buildWithParameters HTTPS 접근 확인
[ ] deploy-agent 재빌드 후 /srv/festa/webgl bind·curl·flock·python 확인
[ ] demo Nginx template 반영 후 nginx -t·reload, Brotli 응답 확인
[ ] 정상 package 실배포 후 current/previous, 공개 헤더 확인
[ ] 강제 검증 실패 후 직전 current 복원, Dedicated Server 무재시작 확인
[ ] token 원문 미검출 확인
```

`specs/infra-001-ci-cd-pipelines/tasks.md` 도 같은 그림이다 — **T022A·C·D·E·F 는 `[X]`, T022B(credential·
접근)만 `[ ]`**. 즉 **코드는 끝났고 실환경 결선만 남았다.**

### 4-3. 그 밖의 수동 절차

- **demo·prod 배포 전체** — `deploy-environment.sh:27` 이 `only dev component deployment is implemented`
  로 끊는다. demo 는 nginx 템플릿(`demo.conf.template`)만 있고 compose 가 없다
- **dev 배포 실패 복구** — `#160`(PostgreSQL credential drift)처럼 credential 이 어긋나면 사람이 개입
- **Jira 상태 전이** — `.gitlab-ci.yml` 의 `jira-sync-*` 3종은 `when: never`. Jira 내장 연동에 맡긴다

---

## 5. 확인 불가

| 항목 | 이유 |
|---|---|
| Jenkins job 실행 이력·성공률 | Jenkins 가 EC2 내부(`ci.ssafesta.world`). 이 세션에 접근 수단 없음 |
| `unity.groovy` 를 실행하는 job | `jobs/*.groovy` 5개 중 지정처 0건. `Jenkinsfile:21` 은 develop 외 브랜치를 에러 처리 |
| demo 환경 FE·BE·AI 의 현재 배포 방법 | 저장소에 demo compose·스크립트가 없음. nginx 템플릿만 존재 |
| WebGL 빌드·업로드 소요 시간 | 로그·기록 없음. `#142` 코멘트의 언급은 측정값이 아님 |
| prod 환경 실재 여부 | 저장소·계약 문서 어디에도 배포 대상이 없음. "없다" 로 단정하지 않고 미구축으로 남김 |

---

## 6. 낡은 서술 정정 — 내가 쓴 것 중 지금 사실과 다른 것

### 6-1. `#165` 본문 (09-10 14:26 작성) — 3곳

| 그때 쓴 것 | 지금 사실 |
|---|---|
| *"`deploy-webgl-release.sh` 는 저장소에 아직 없습니다(T022C 로 계획만)"* | **`-581`(`39573a56`, 15:02)로 211줄 신설됐다.** T022C 는 `[X]` |
| *"`specs/infra-001` T022A~F 는 아직 미착수"* | **T022A·C·D·E·F 가 `[X]`.** 미착수는 T022B(credential·접근) 하나 |
| *"Builds/webgl 이 Unity Agent workspace 밖으로 나가지 못한다"* | 여전히 사실이나 **이유가 바뀌었다** — `7095ca1e` 로 **일반 game CI 에서 WebGL 빌드를 의도적으로 뺐다.** 반출이 막힌 것이 아니라 만들지 않는다 |

`#165` 의 blocker ②(`webglArtifactUri` 생산·소비 0)·③(deploy 필터 game 제외)은 **여전히 사실이다**
(`build-release-manifest.sh:41` · `develop.groovy:32` 실측).

### 6-2. `docs/LJH/verify/unity-webgl-build-deploy-pipeline-0910.md` (오늘 오전)

`:474`·`:518`·`:529`·`:649` 가 *"`deploy-webgl-release.sh` 없다 — 신설"* 로 적혀 있다. **6-1 과 같은 사유로
낡았다.** 그 문서는 조사 시점 기록이므로 본문을 고치지 않고, 이 문서가 후속 상태를 갖는다.

### 6-3. `#163` — 확정과 반영을 갈라야 한다

인프라 확정(14:53)은 왔지만 **저장소에는 아직 없다.**

```
develop.groovy:142                    PUBLIC_API_BASE_URL=/__dev/api      <- 옛 값
public-entry-contract.md:11           http://${EC2_PUBLIC_IP}/__dev/...   <- 옛 계약
nginx/sites/dev.conf                  /__dev/front · /__dev/api · /__dev/ai, /unity 0건
```

→ **계약 확정 / 실제 미반영.** wire 는 `-586` T088~T090 이다.

---

## 7. 후속 자동화 후보 — 수동 비용·빈도·실패 영향 순

| 순위 | 후보 | 근거 | 소유 |
|---|---|---|---|
| 1 | **WebGL preflight 9건 결선** | 코드는 다 있고 credential·job 등록만 남았다. 풀리면 릴리스당 수동 절차가 1회 스크립트로 줄고 09-09 같은 폐기 2건도 검증으로 걸린다 | **Infra** |
| 2 | **`#163` 확정을 파이프라인에 반영** | `develop.groovy:142` 가 폐기 예정 값을 주입 중이다. dev HTTPS 전환 시 그대로면 깨진다 | **Infra** (`-586` T088~T090) |
| 3 | **WebGL build 자동화 여부 결정** | 지금은 `7095ca1e` 로 의도적 제외다. 자동화할지, 담당자 PC 유지가 정본인지 **결정 자체가 없다** | **Game + Infra** |
| 4 | **demo 배포 경로** | `deploy-environment.sh:27` 이 dev 만 구현. demo 는 수동인지 미구축인지도 불명 | **Infra** |
| 5 | **dev 배포 실패 2건 해소** | `#160` credential drift · `-555` AI healthcheck 경로 | **Infra + BE/AI** |
| 6 | **AI MR 게이트 병합** | `!588`·`!605`·`!607` 3개가 같은 목적으로 열려 있다 | **Infra** |

**FE 몫은 이 목록에 없다.** FE 는 `-564`·`-567` 로 주입 계약을 이미 세웠고(`#163` 대조 결과 변경 0),
남은 것은 전부 Infra·Game 소유다.

---

## 8. 이 조사에서 하지 않은 것

`infra/`·Jenkins·Unity 파일 수정, 실제 배포·Jenkins 트리거 실행, 새 자동화 구현, `#165`·`#167` 종결.
전부 범위 밖이다.
