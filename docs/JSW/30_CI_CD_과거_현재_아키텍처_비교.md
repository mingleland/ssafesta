# CI/CD 과거와 현재 아키텍처 비교 — JSW

> 초기 이상 중심의 설계(과거)와 수많은 장애·병목을 겪으며 실전에 맞게 정합화한 설계(현재)의 1:1 대조 정본 문서입니다.

---

## 1. 개요 및 설계 철학 변화

| 구분 | 과거 (초기 이상적 설계) | 현재 (실전 정합화된 설계) |
|---|---|---|
| **핵심 기조** | "모든 것을 자동화하고 완벽한 게이트를 통과해야만 배포된다" (이론 중심) | "현실적인 인프라 리소스 한계(4 vCPU/15GB)를 인정하고, 병목과 위험을 제거한다" (실전 중심) |
| **통합 환경** | `dev.ssafesta.world` (자동) ➔ `demo.ssafesta.world` (승격) 이원화 | `dev` 가상 환경 폐기, `demo.ssafesta.world` 가 `develop` 통합 Staging 전담 |
| **최종 배포선** | `demo.ssafesta.world` 가 최종 시연 환경 | `main` 브랜치 ➔ `https://ssafesta.world` 실제 프로덕션(Production) |
| **검증 방식** | 4단계 자동화 (스모크 러너가 직접 헤드리스 플레이어로 접속 검증) | 1~3단계 네트워크/WSS 자동 검증 + 브라우저 실접속 팀 검증 |

---

## 2. 세부 영역별 1:1 대조

### (1) 브랜치 전략 및 파이프라인 생성 (Workflow)

- **과거**:
  - 소스 브랜치가 반드시 `feat/`, `fix/`, `chore/` 등 정규식에 일치해야만 `develop` 대상 파이프라인이 생성됨.
  - 파트 브랜치(`front`, `back`, `ai`, `game`)에서 `develop`으로 올리면 파이프라인이 0개로 떠서 `Pipelines must succeed`에 걸려 머지가 영구 잠김.
  - 이를 우회하기 위해 `chore/S15P21A604-xxx-front-to-develop` 같은 꼼수 임시 브랜치를 매번 생성해야 했음.
- **현재 (Phase 1 반영)**:
  - 소스 브랜치 명명 규칙과 무관하게, `develop`을 향하는 MR이면 무조건 정상 파이프라인 생성.
  - 가벼운 기능은 직통(`feat/*` ➔ `develop`), 무거운 백엔드 작업은 파트 브랜치 모음(`back` ➔ `develop`) 둘 다 자유롭게 지원.

---

### (2) 컴포넌트 변경 감지 (Change Detection)

- **과거 (merge-base 누적 diff)**:
  - GitLab `rules:changes`의 기본 동작으로 인해, 두 브랜치가 갈라진 옛날 공통 조상부터 누적된 커밋 로그를 전부 훑음.
  - 파트 브랜치에서 과거에 `.gitlab-ci.yml`을 단 한 번이라도 건드렸다면, 스쿼시 머지 후에도 그 이력이 남아 **프론트엔드 버튼 하나만 고쳐도 백엔드, AI, Unity 검증까지 4개 파트가 전부 실행**됨 (러너 20분 독점).
- **현재 (Phase 2 Net Diff)**:
  - `compare_to: 'refs/heads/develop'` 옵션 적용.
  - 과거 이력을 무시하고 **"현재 develop 최신 트리 vs 현재 소스 브랜치의 최종 트리"의 순수 차이(Net Diff)**만 대조.
  - 이번 MR에서 실제로 CI 파일이 바뀌지 않았다면, 프론트엔드 변경 시 프론트만(1~2분) 정확히 실행.

---

### (3) Unity MR 검증 (Unity MR Validation)

- **과거 (동기 폴링 45분)**:
  - GitLab Runner가 Jenkins에 Unity EditMode 검증을 요청한 뒤, 완료될 때까지 `while sleep`으로 **최대 45분 동안 혼자 멀뚱히 대기**.
  - 동시 실행 2개뿐인 러너 슬롯 하나를 45분 동안 묶어두어 팀 전체 파이프라인이 올스톱됨.
- **현재 (Phase 3 비동기 디스패치 & 콜백)**:
  - GitLab Runner는 Jenkins에 검증 트리거만 curl로 쏘고 **5초 이내에 정상 종료(exit 0)하여 러너 슬롯 즉시 반환**.
  - Jenkins Unity 에이전트가 백그라운드에서 검증을 마친 후, GitLab API(`gitlabCommitStatus`)로 `unity-mr-validation` 성공/실패 상태를 커밋에 비동기 콜백.
  - GitLab 머지 체크는 이 비동기 뱃지가 초록불이 될 때까지 머지를 안전하게 차단.

---

### (4) Unity 전용 서버(Dedicated Server) 배포 게이트

- **과거 (스모크 러너 의무화)**:
  - 4단계 게이트: 프로세스 실행 ➔ 7777 내부 포트 ➔ 외부 WSS 101 ➔ **실제 플레이어 승인 접속(Smoke Runner)**.
  - 스모크 러너(Linux Headless Player)를 유니티로 빌드하려면 셰이더 컴파일로 **1회 3시간**이 걸려 현실적으로 빌드/제공 불가능.
  - 산출물이 없으니 매번 `NOT_BUILT`로 건너뛰거나, 억지로 수동 배포하다가 WebGL과 서버 프리팹이 어긋나 클라이언트가 즉사(`function signature mismatch`)하는 사고 발생.
- **현재 (3단계 자동 검증 + 프리팹 트리 가드)**:
  - 스모크 러너 자동화는 3시간 비용으로 인해 **공식 제외(보류, `approvedAdmission: SKIPPED`)**.
  - 1~3단계(프로세스, 내부 포트, WSS 101) 통과로 `candidate ➔ current` 자동 승격 확정.
  - `deploy-game.sh`에 **WebGL manifest와 서버 후보의 프리팹 트리 해시 대조 가드(T032a, exit 75 SKIP)**를 두어 짝짝이 배포 원천 차단.
  - 최종 사용자 승인 검증은 실제 브라우저 실접속 후 `known-good`으로 수동 확정.

---

### (5) 배포 책임 분리 (Promotion & Invariants)

- **과거**:
  - `develop`에 머지되면 즉시 배포가 트리거됨.
  - 배포가 실패해도 코드는 이미 `develop`에 머지되어 있어 롤백이 불가능하고 깃랩 뱃지는 상시 빨간불(Red) 도배.
  - 10분 간격으로 머지가 들어오면 이전 배포가 취소(`SUPERSEDED`)되어 실제 배포가 건너뛰어짐.
- **현재**:
  - `develop`은 "통합 검증(Integration)", `main`은 "프로덕션 배포 기준선(Deployment Approved)"으로 책임 완전 분리.
  - **Architecture Invariant**: `develop ➔ main` 승격 MR은 **절대 Squash 금지 (`squash=false` 강제)**. 커밋 계보를 100% 보존하여 다음 승격 시 전체 커밋이 다시 diff로 잡히는 현상 방지.
  - **Artifact Invariant**: `main` 승격 시 코드를 새로 빌드하지 않고, demo에서 검증 완료된 **동일한 불변 Docker 이미지(`known-good`)를 프로덕션에 그대로 재사용**.
