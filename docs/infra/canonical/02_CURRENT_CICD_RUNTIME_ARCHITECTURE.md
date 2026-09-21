# SSAFY FESTA Current CI/CD & Runtime Architecture

> 상태: CURRENT / FROZEN
> 기준: 2026-09-21 최종 인프라 개편 완결

---

## 1. Branch / Environment Governance

```text
feature / fix / part branch
        ↓
GitLab MR pipeline (pre-merge validation, 12~14초)
        ↓
      develop (integration approved)
        ↓
Jenkins develop execution (Demo batch deploy)
        ↓
       Demo (runtime verification)
        ↓
Demo current / known-good / approval
        ↓
develop → main NON-SQUASH (promotion baseline)
        ↓
manual Production Promotion (Jenkins exact artifact 승격)
        ↓
    Production (CURRENT = KNOWN-GOOD)
```

---

## 2. Unity 경로 분리 및 정본 체계

### A. Unity Release Producer (Consumer-only)
- **주체**: Unity 담당자 licensed environment (개발자 로컬 워크스테이션)
- **산출물**: 4-file Release Bundle (`festa-webgl-release-<8sha>.zip`, `festa-game-<8sha>.tar`, `webgl-manifest.json`, `image-metadata.json`)
- **저장소**: GitLab Generic Package Registry (`unity-release-bundle/<8sha>`)
- **수행**: Jenkins Consumer가 아티팩트 다운로드 및 검증 후 Demo candidate / Production candidate로 배포
- **불변조건**: **Jenkins Unity Release Build = 0, Jenkins WebGL Build = 0, Jenkins Game Build = 0**

### B. Unity MR Validation (EditMode Test)
- **주체**: GitLab CI `unity-mr-validation-dispatch` job
- **동작**: `festa-unity-mr-validation` Jenkins job을 비동기 트리거(curl, 7초 반환)
- **실행 노드**: `festa-jenkins-agent-unity` (`unity-6000.0.78f1` 라벨)
- **필수 런타임**: `/opt/unity` (Unity Editor 6000.0.78f1 본체, 읽기 전용 마운트)
- **결과 콜백**: Jenkins가 `unity-mr-validation` commit status를 GitLab MR에 비동기 게시

---

## 3. Observability 체계

### A. OPERATING OBSERVABILITY (ACTIVE / CLOSED)
- **호스트**: Linux native PSI (`/proc/pressure/{cpu,memory,io}`), `free`, `vmstat`, `iostat`, `docker ps/stats`
- **애플리케이션**: Spring Boot Actuator (`/actuator/health`), FastAPI (`/ai/v1/health/live`, `/ready`), Nginx 101 WSS 프로브
- **CI/CD**: GitLab Pipeline API (`duration`, `queued_duration`), Jenkins API (`builds[number,result,duration]`)

### B. MONITORING STACK (READY / ON-DEMAND)
- `infra/observability/compose.yaml` (Alloy, Prometheus, Loki, Grafana, cAdvisor): **상시 가동 아님**. 필요 시 `docker compose up -d`로 즉시 기동 가능한 온디맨드 스택으로 보존.

---

## 4. Docker Retention 정본 체계

- 단순 "컴포넌트당 5개"가 아님.
- **보호 대상**:
  `최근 retention window(5) + CURRENT + KNOWN-GOOD + rollback/previous + release history + state/receipt/candidate references`
- `deploy_state` 볼륨 내 릴리스 이력과 영수증에서 참조하는 고유 이미지 344건이 전수 보호되므로, 컴포넌트별 이미지가 5개보다 많이 남아 있는 것은 **정상 보호 상태**임.

