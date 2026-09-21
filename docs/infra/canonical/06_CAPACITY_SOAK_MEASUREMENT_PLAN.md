# Runner Capacity & Backlog Soak Measurement Report — Final

> 상태: MEASUREMENT COMPLETE & VERIFIED (FROZEN)
> 측정 시각: 2026-09-21 KST

---

## 1. Mixed Backlog Soak 실측 결과

실제 팀의 개발 혼합 백로그 시나리오(Front BUILD/TEST, Back BUILD/TEST, AI BUILD/TEST, Docs FAST mr-status)를 동시 수주하여 측정한 실측값입니다.

### A. Concurrent = 4 Mixed Soak
- **MR 세트**: `!1258` (Front), `!1259` (Back), `!1260` (AI), `!1261` (Docs) 동시 투입
- **러너 슬롯**: `builds=1 → 2 → 3 → 4 / max_builds=4` 실시간 동시 수주
- **Queue 대기 시간**:
  - Front: 1초
  - Back: 2초
  - AI: 0초
  - Docs (FAST): 1초
- **소요 시간 (Duration)**:
  - FAST mr-status: **13.7초 / 15.8초** (단독 실행 대비 악화 없음)
  - Front 빌드/테스트: 64~66초
  - Back 빌드/테스트: 52초
- **호스트 리소스 (4 슬롯 풀 가동 중)**:
  - Load average: **1.31**
  - CPU PSI: some 18.39 / **full 0.00**
  - Memory PSI: **0.00 / 0.00**
  - Swap: 917MB (변동 0)
  - IO PSI: some 4.71 / full 2.01 (안정 범위)

### B. Concurrent = 5 Mixed Soak (한계 확인)
- **MR 추가**: `!1262` (복합 변경) 투입 (`builds=5 / max_builds=5`)
- **실측 리소스 반응**:
  - Load average: **3.47 → 10.82** 폭등
  - IO PSI: some **53.72** / full **32.25** (I/O 병목 경보)
  - Memory PSI: some **11.38** / full **9.58** (메모리 압박 발생)
  - Swap: **918MB → 1.4GB** (스왑 인/아웃 발생)
- **결론**: 4 vCPU 단일 호스트의 안전 상한은 **Concurrent = 4**로 명확히 확정.
- **c=6**: c=5에서 명백한 스톱 조건이 충족되었으므로 추가 실행하지 않음 (`NOT RUN`).

---

## 2. 최종 병목 판정

```text
FINAL_CONCURRENT = 4
FINAL_LIMIT = 4
FINAL_REQUEST_CONCURRENCY = 4
FINAL_TOPOLOGY = Single Docker Runner on Same Host
FINAL_BOTTLENECK = NONE (at concurrent=4)
```

