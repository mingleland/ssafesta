# GitLab Runner Capacity Architecture — Final Verified Revision

> 상태: CURRENT / VERIFIED OPERATIONAL BASELINE (FROZEN)
> 확정 시각: 2026-09-21 KST

---

## 1. Capacity 단계별 실측 비교표

| 항목 | c=2 (Safe Baseline) | c=4 (Final / Optimal) | c=5 (Ceiling Exceeded) | c=6 (Not Run) |
|---|---|---|---|---|
| **FAST queue 지연** | 0~2초 | **0~1초** | 2~3초 | - |
| **BUILD queue 지연** | 0초 (단일 작업 시) | **0~2초** (4개 동시 유입 시) | 2~5초 (큐 적체 시작) | - |
| **Front 소요 시간** | 63초 (build) | **64~66초** (경미 +2%) | **75~85초** (+25% 악화) | - |
| **Back 소요 시간** | 51초 (build) | **52초** | **65초+** | - |
| **AI 소요 시간** | 53초 (test) | **54초** | **60초+** | - |
| **Host Load Average** | 1.10 | **1.31** (4 vCPU 기준 안정) | **3.47 → 10.82** (CPU 포화) | - |
| **CPU PSI some / full** | 6.74 / 0.00 | **18.39 / 0.00** (기아 없음) | **20.92 / 0.00** | - |
| **Memory PSI some / full**| 0.00 / 0.00 | **0.00 / 0.00** (압박 없음) | **11.38 / 9.58** (메모리 경보) | - |
| **Swap 사용량** | 918MB (변동 0) | **917MB (변동 0)** | **1.1GB → 1.4GB** (스왑 발생) | - |
| **IO PSI some / full** | 1.95 / 1.38 | **4.71 / 2.01** (안정 범위) | **53.72 / 32.25** (I/O 급증) | - |
| **Jenkins 영향도** | 없음 | **지연 0초 / 즉시 수주** | 리소스 경합 발생 | - |
| **Demo 런타임 영향** | 없음 | **healthy / WSS 101 유지** | 영향 가능성 | - |
| **종합 판정** | SAFE BASELINE | **FINAL / OPTIMAL** | **CEILING EXCEEDED** | **NOT RUN (Stop 충족)** |

---

## 2. 최종 확정값 및 근거

- **FINAL_CONCURRENT**: `4`
- **FINAL_LIMIT**: `4`
- **FINAL_REQUEST_CONCURRENCY**: `4`
- **FINAL_TOPOLOGY**: **Same-host Single Runner (Option A 확정)**
- **FINAL_BOTTLENECK**: **NONE (at concurrent=4)**

### `request_concurrency = 4` 근거
- build concurrency와 동일 개념이어서 4인 것이 아니라, job pickup / long-poll 동작에서 발생하던 큐 병목 경고를 완전히 해소하고 즉시 잡 수주(0초)를 보장하는 현재 운영 설정으로 검증됨.

### c=5 한계 초과 및 c=6 Not Run 근거
- `concurrent = 5`에서 5개 작업 동시 실행 시 Load average `10.82`, IO PSI `53.72`, Memory PSI `11.38`, Swap `1.4GB`로 급증하여 단일 4-vCPU 호스트의 명백한 물리 상한이 확인됨.
- 따라서 사전 정의된 중단 조건(Stop condition)이 충족되어 `c=6`은 추가 실행하지 않고 `c=4`를 최적 운영값으로 확정함.

