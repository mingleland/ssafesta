# SSAFY FESTA Infra Master Index

> 기준 시각: 2026-09-21 KST (최종 Freeze)
> 목적: 새 세션, 인프라 담당자, 팀원이 과거 이력을 다시 추측하지 않고 현재 정본에서 즉시 작업을 이어가도록 하는 단일 진입점.
> 저장소 운영 정본: GitLab `lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604.git`
> 운영 checkout: `/home/ubuntu/festa/S15P21A604`

---

## 0. 현재 판정 (Final Status)

```text
BATCH 1                       = COMPLETE
BATCH 2 CONSUMER E2E          = COMPLETE
BATCH 3                       = COMPLETE

FINAL_CONCURRENT              = 4 (OPTIMAL)
FINAL_LIMIT                   = 4
FINAL_REQUEST_CONCURRENCY     = 4
FINAL_TOPOLOGY                = Same-host Single Runner

c=2                           = SAFE BASELINE
c=4                           = FINAL / OPTIMAL
c=5                           = CEILING EXCEEDED
c=6                           = NOT RUN (c=5 stop condition 충족으로 추가 실행 불필요)
Separate CI Host              = NOT REQUIRED
FAST/BUILD Lane Split         = NOT REQUIRED

UNITY PATH CLASSIFICATION     = COMPLETE (Producer: Consumer-only / MR: EditMode validation)
SECURITY CLOSURE              = CURRENT_GENERATION_ACCEPTED
OBSERVABILITY CLOSURE         = OPERATING ACTIVE / ON-DEMAND STACK READY
DOCKER RETENTION CLOSURE      = 344 PROTECTED REFERENCES VERIFIED
#220 CI/CD RESTRUCTURING      = CLOSED (DoD A~I SATISFIED)
INFRA DOCUMENTATION           = FROZEN
INFRA HANDOFF                 = READY
```

---

## 1. 확정된 최종 아키텍처

- **GitLab Runner**: 단일 호스트 Docker Runner, `concurrent = 4`, `limit = 4`, `request_concurrency = 4`
- **Jenkins**: deploy 에이전트(1, 배포/검증 전용), linux-docker 에이전트(1, 빌드/테스트 전용, PyYAML 고정 핀/preflight), unity 에이전트(1, MR EditMode validation 전용)
- **Unity Release Producer**: **Consumer-only** (Unity 담당자 PC 빌드 → Generic Package Registry 번들 반입 → Jenkins Consumer 배포; CI 내 릴리스 빌드 0)
- **Unity MR Validation**: GitLab CI `unity-mr-validation-dispatch` → Jenkins `festa-unity-mr-validation` → Unity Agent (`/opt/unity` 사용)
- **Demo / Production**: 완전 분리 (Demo 17777 / Prod 27777, exact-artifact 승격)

---

## 2. Production Carry-forward (향후 운영 이벤트 4건)

1. 실제 GitLab provenance를 가진 Unity 번들 입고 시 canonical gate 1회 확인 (`CANONICAL_PROVENANCE_PENDING`)
2. Production AI/Back 환경 파일의 stale/dead 키 정리 (다음 프로덕션 승격 시 편승)
3. Production Gate G 실측 (Human Gate 대기 중 deploy executor 즉시 해제 동작 실측)
4. Raffle 당첨 인원/추첨 시각 확정 시 운영 manifest에 응모형 경품 추가 및 provisioning

