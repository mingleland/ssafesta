# FE / BE / AI 개발 → Demo 반영 가이드

> 팀원용 실무 가이드. 정확한 구조는 [`../canonical/02_CURRENT_CICD_RUNTIME_ARCHITECTURE.md`](../canonical/02_CURRENT_CICD_RUNTIME_ARCHITECTURE.md)를 따릅니다.

## 1. 기본 흐름

```text
작업 브랜치
↓
MR
↓
GitLab CI
↓
develop
↓
Jenkins `festa-gitlab-develop`
↓
변경 component build / test / deploy
↓
Demo
```

GitLab CI는 **merge 전 gate**, Jenkins는 **develop merge 후 실행/배포**입니다.

---

## 2. develop 대상 MR

`develop` 대상 MR에서는 변경 component에 따라 CI가 선택적으로 실행됩니다.

| 변경 | 대표 GitLab CI |
|---|---|
| Front | `front-test` → `front-build` |
| Back | `back-test` → `back-build` |
| AI | `ai-test` → `ai-build` |
| Unity | `unity-mr-validation-dispatch` |
| 공통 | `mr-status` |

`mr-status`는 MR diff 범위 secret scan과 pipeline status 역할을 합니다.

---

## 3. 파트 브랜치 대상 MR 주의

현재 `.gitlab-ci.yml`에는 `ai`, `back`, `front`, `frontend*`, `game` 같은 파트 브랜치 대상 MR을 위한 경량 경로가 남아 있습니다.

이 구간에서는 **`mr-status`만 실행되고 component CI는 develop 대상 MR에서 수행**됩니다.

즉:

```text
파트 브랜치 MR
→ 경량 status

파트 브랜치 → develop MR
→ 실제 component CI
```

파트 브랜치에서 통과했다고 develop CI까지 끝난 것은 아닙니다.

---

## 4. develop merge 후

develop push는 Jenkins multibranch job `festa-gitlab-develop`이 처리합니다.

개발자가 일반적으로 하지 않아도 되는 것:

```text
Demo 서버 SSH 접속
docker compose 직접 실행
Demo image 수동 변경
Jenkins develop 수동 클릭
```

변경 component 판정 후 필요한 build/deploy만 실행됩니다.

---

## 5. 자동화 범위

```text
MR validation        자동
Auto-merge 등록 후 merge 처리  GitLab
develop 이후 Demo 실행         Jenkins
Production promotion           별도 수동 절차
```

**develop merge는 Production 배포가 아닙니다.**

---

## 6. MR이 느릴 때

현재 Runner 최종값:

```text
concurrent = 4
limit = 4
request_concurrency = 4
```

정상 mixed workload에서 FAST queue는 대략 0~1초, BUILD queue는 0~2초 범위가 실측 기준입니다.

평소보다 queue가 크게 늘면 코드를 반복 push하기 전에 Runner/Jenkins 상태를 먼저 확인합니다.

---

## 7. 문제 보고 시 같이 전달할 정보

```text
MR 번호
source commit SHA
변경 component
실패한 GitLab job 이름
develop merge 이후라면 Jenkins build 번호
실패 stage / 대표 오류 1~2줄
```

이 정도면 인프라 담당자가 빠르게 경로를 좁힐 수 있습니다.
