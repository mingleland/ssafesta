# SSAFY FESTA Infra Handoff Package — Final (FROZEN)

> 대상: 인프라 담당자 / 후속 세션
> 기준: 2026-09-21 (인프라 대개편 완결)

---

## 1. Final Verified Topology

- **Host**: Single EC2 (4 vCPU, 15GB RAM, 309GB NVMe)
- **GitLab Runner**:
  - `concurrent = 4` (최종 확정)
  - `limit = 4`, `request_concurrency = 4`
  - `executor = "docker"`, `network_mode = "host"`
  - `volumes = ["/var/run/docker.sock:/var/run/docker.sock", "/cache"]`
- **Jenkins Nodes**:
  - `Built-In Node`: 0 executors (EXCLUSIVE)
  - `deploy` agent: 1 executor (배포/검증 전용)
  - `linux-docker` agent: 1 executor (테스트/빌드 전용, PyYAML 고정 핀 및 preflight 검증)
  - `unity` agent: 1 executor (Unity MR EditMode validation 전용)
- **Runtimes**:
  - **Demo**: Front, Back (`demo.ssafesta.world`, WSS 101), AI (live/ready UP), World (TCP 17777 / WSS 101), WebGL (`releases/9b9c6860`)
  - **Production**: Front (28080), Back (28081), AI (28082), World (27777), WebGL (`releases/9b9c6860`), CURRENT = KNOWN-GOOD = `demo-approved-6169b211-20260920T091000Z`

---

## 2. Unity Path Classification
- **Unity Release Producer**: **Consumer-only** (Unity 담당자 PC 빌드 → Generic Package Registry 번들 반입 → Jenkins Consumer 배포; CI 내 릴리스 빌드 0).
- **Unity MR Validation**: GitLab CI `unity-mr-validation-dispatch` → Jenkins `festa-unity-mr-validation` → Unity Agent (`/opt/unity` 사용 EditMode 검증).

---

## 3. Security Closure
- **Jenkins Agent Secret**:
  - 인바운드 에이전트 WebSocket 연결 시 `-secret`은 공식 `jenkins/inbound-agent`의 표준 동작임.
  - 컨테이너 격리 및 `no-new-privileges:true` 적용, host-root 외 노출 불가.
  - 판정: **`CURRENT_GENERATION_ACCEPTED`**.

---

## 4. Observability Closure
- **OPERATING OBSERVABILITY**: **ACTIVE / CLOSED** (Linux native PSI, free, vmstat, docker stats, Spring Actuator, FastAPI health, WSS 101, deploy_state)
- **MONITORING STACK**: **READY / ON-DEMAND** (`infra/observability/compose.yaml`)

---

## 5. Capacity Final
- `c=2`: SAFE BASELINE
- `c=4`: **FINAL / OPTIMAL**
- `c=5`: CEILING EXCEEDED
- `c=6`: **NOT RUN** (c=5 stop condition 충족으로 추가 실행 불필요)
- `FINAL_REQUEST_CONCURRENCY = 4`

---

## 6. Legacy & Docker Closure
- `/opt/unity` (14GB): `festa-unity-mr-validation` (EditMode 검증) 지원을 위해 보존 (`REQUIRED / PRESERVED`).
- Docker Images: 잔여 178개 이미지는 상태 머신 및 롤백 영수증이 참조하는 344개 고유 레퍼런스에 포함된 정상 보호 대상임이 전수 대조됨.
- GitLab Packages: stale/test 패키지 8개 삭제 완료 (204 No Content).

---

## 7. #220 최종 상태
- **GitLab Issue #220**: **CLOSED** (DoD A~I 실측 증거 매핑 및 종료 코멘트 등록 완료).

---

## 8. Production Carry-forward (유일한 잔여 4건)
1. 실제 GitLab 소스 커밋 provenance가 있는 Unity 번들 입고 시 canonical gate 1회 확인 (`CANONICAL_PROVENANCE_PENDING`)
2. Production AI/Back 환경 파일의 stale/dead 키 정리 (다음 프로덕션 배포 시 편승 반영)
3. Production Gate G (Human Gate 대기 중 deploy executor 즉시 해제 동작 실측)
4. Raffle 당첨 인원/추첨 시각 확정 시 운영 manifest(`event-prizes.json`)에 치킨 추가 및 provisioning

