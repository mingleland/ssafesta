# SSAFY FESTA Infra Evolution Timeline

> 목적: 현재 구조를 결과만 보고 오해하지 않도록, 어떤 문제를 거쳐 어떤 설계가 채택/폐기됐는지 시간순으로 보존한다.

---

## 1. 출발점 — develop/main 경계와 CI/CD 재정의

### 2026-09-16 — #220 제안

핵심 문제:

- develop 통합과 main 배포의 의미가 섞여 있었음
- part-branch batching + squash 때문에 단순 `rules:changes` 가정이 깨짐
- Jenkins develop 배포가 간헐적으로 연속 실패
- Runner 1대, 제한된 capacity에서 MR과 배포가 서로 영향을 줌

정의된 방향:

```text
develop = integration approved
main    = deployment candidate / production baseline
Jenkins success = deployment verified
```

절대 규칙:

```text
develop → main promotion = NON-SQUASH
```

이 시점부터 GitLab은 pre-merge validation, Jenkins는 deployment/execution이라는 책임 분리가 정본으로 자리잡기 시작했다.

---

## 2. 2026-09-19 — Runtime Foundation 위기

당시 실환경은 CI 문제만이 아니었다.

### restart loop

대표 실측:

```text
festa-prod-back-1 restart_count ≈ 4958
festa-prod-ai-1  restart_count ≈ 2529
festa-demo-back-1 restart_count ≈ 10
```

### CPU / host pressure

CI를 취소한 상태에서도:

```text
steal ≈ 58~61%
idle ≈ 0%
run queue ≈ 20~40
```

### Jenkins zombie

약 5,925개의 zombie process가 누적됐다.

원인 축:

- Jenkins agent Java가 container PID 1
- init/reaper 부재
- 다수 runtime/legacy 환경 공존
- CI + Jenkins + runtime + Unity 작업이 동일 4-vCPU host에 중첩

대응:

- Jenkins agent 공통 compose `init:true`
- legacy/runtime 정리
- Runner concurrency를 보수적으로 2→1
- "지금은 throughput보다 foundation hardening"으로 우선순위 변경

중요: 이때의 `concurrent=1`은 최종 capacity 정답이 아니라 **불안정한 foundation을 살리기 위한 응급 제한**이었다.

---

## 3. Production exact-artifact 구조 확정

Production을 `dev/demo/prod`의 세 번째 개발환경처럼 일반화하는 접근은 폐기됐다.

채택된 모델:

```text
develop에서 artifact 생성
→ Demo 실제 소비
→ 자동 readiness + human verification
→ Approved Demo Receipt
→ develop → main NON-SQUASH
→ Production에서 build/repack 없이 exact artifact 승격
```

핵심:

- App image identity 고정
- WebGL immutable package 고정
- World immutable package/image identity 고정
- main ancestry gate
- Production candidate/internal verification
- public activation
- human verification 후 known-good

Production state model:

```text
candidate → CURRENT/public → human verify → KNOWN-GOOD
```

CURRENT와 KNOWN-GOOD를 같은 트랜잭션으로 갱신하는 접근은 금지됐다.

---

## 4. Production #8 완주

2026-09-20 기준 Production promotion #8 SUCCESS.

최종 state:

```text
CURRENT = KNOWN-GOOD
= demo-approved-6169b211-20260920T091000Z
```

Runtime:

```text
Front 6169b211...
Back  55ba2d66...
AI    55ba2d66...
World 367f9cdd...
WebGL 9b9c6860
```

이 시점에서 exact-artifact Production promotion chain은 기능적으로 완료됐다.

---

## 5. 2026-09-20 Full Audit — "배포는 됐지만 구조는 아직 비효율"

Full Audit에서 발견된 핵심:

### GitLab Runner

- 1 project Runner
- Docker executor
- `concurrent=1`
- host network
- docker socket mount
- no tags
- queue 최대 약 18분

### develop/Demo

- infra/ci 변경 시 `shared-ci`가 deployComponents를 비워 실제 Demo 배포가 자주 생략
- 최근 10개 develop build 중 8개가 deploy 없음

### Demo World

- Demo/Production route 분리 불완전
- Demo readiness가 Production World를 검사해 false-positive 가능

### Unity

- Jenkins Unity build가 license 문제로 실패
- WebGL publish 정상 경로 미자동화
- LFS는 실제로 0 objects
- Unity Editor + workspace가 host에 큰 footprint

### 운영 기능

- raffle FE가 mock 고정
- operational business data provisioning 부재
- AI DB env dead config
- Back WORLD_* stale override
- Docker/Registry hygiene 부재
- MR secret scan이 지나치게 비쌈

이 Audit을 기준으로 Batch 1~3가 구성됐다.

---

## 6. Batch 1 — CI boundary + Demo/Prod isolation

목적:

- develop→main MR이 열려도 Jenkins develop branch가 사라지지 않게 함
- shared-ci와 실제 deploy 대상 분리
- Demo World와 Production World를 명확히 분리
- partial component current 조합을 receipt로 승인 가능하게 함
- Production human gate가 deploy executor를 붙잡지 않게 함

주요 결과:

- multibranch discovery 전략 보정
- detector validation/build/deploy/shared 축 분리
- validate-only game 경로는 Unity node 불필요
- content-derived batchId
- Production validator exact batchId
- production pipeline `agent none`, gate stage no-agent
- Demo WSS root route → 17777
- Production WSS root → 27777

Batch 1 완료 후 남은 것은 다음 실제 Production promotion의 Gate G 실측뿐이다.

---

## 7. CI/EC2 10x 최적화

Batch 2/3 도중 CI가 지나치게 비효율적이어서 우선 최적화 회차가 들어갔다.

핵심 변경:

- component change rules에서 불필요한 `ci/**/*` 전파 제거
- CI-only 변경은 lightweight contracts/static만 수행
- develop pipeline CI-only → NO_OP
- Backend Testcontainers layer 분리
- non-DB backend pure unit ~356 tests를 15~16초 수준으로 축소
- Ryuk 복구 + owner-scoped fallback cleanup
- Maven output reuse는 같은 `CI_COMMIT_SHA + CI_RUN_ID`에서만 허용
- npm/AI dependency cache
- leaked Testcontainers / old dev containers / legacy network 정리

효과:

```text
CI-only MR 12~15분 → 42초
Jenkins CI-only → 35.5초
```

이후 Batch 3 secret-scan diff scope 적용으로 MR은 약 10~14초까지 단축됐다.

---

## 8. Batch 2 — Unity 설계의 큰 전환

### 초기안

초기 Batch 2는 Jenkins가 Unity를 직접 빌드하는 구조였다.

```text
license preflight
→ WebGL build
→ Linux Server build
→ package
→ Registry
→ Demo
```

실환경에서 드러난 문제:

- Unity Personal entitlement / machine binding 불안정
- agent recreate 후 headless entitlement visibility 붕괴
- Jenkins/infra 사용자가 Unity 인증을 떠맡는 구조가 됨

### 최종 전환 — Consumer-only

최종 정본:

```text
Unity 담당자 정상 licensed 환경
→ 4-file Release Bundle 생성
→ GitLab Generic Package Registry
→ Jenkins Consumer
→ validate / publish / Demo candidate / readiness / current
```

폐기:

- Jenkins Unity Editor build
- CI Unity license requirement
- hostname/MAC license acceptance

유지:

- artifact SHA / metadata / lineage validation
- immutable package identity
- Demo candidate/readiness/promotion/restore

---

## 9. Batch 2 artifact identity correction

중요한 추가 수정:

원래는 develop HEAD가 바뀔 때마다 Unity bundle을 다시 찾지 못하는 구조였다.

수정 후 identity 축:

```text
pipelineCommit
artifactSourceCommit
unityInputId
```

즉 unrelated FE/BE/CI merge가 Unity artifact를 무효화하지 않는다.

`CI_COMMIT_SHA`를 artifact source SHA로 덮어쓰는 식의 혼합도 금지했다.

---

## 10. 558d6624 fixture E2E

실제 bundle `558d6624`는 artifact validation은 통과했지만 GitLab source commit이 없었다.

따라서:

```text
OFFLINE / E2E FIXTURE ONLY
CANONICAL_PROVENANCE_PENDING
```

로 격리했다.

E2E에서는 source existence/ancestry만 fixture exception으로 분리하고 다음은 그대로 검증했다.

- metadata
- SHA
- WebGL/Game lineage
- build profile
- sourceCommit consistency
- contentId
- Demo candidate
- readiness
- restore

최종 Jenkins `festa-unity-bundle-e2e #4` SUCCESS.

Unity Editor 실행 0, license 요구 0, Production mutation 0.

---

## 11. Codex polling 문제와 운영 원칙

GitLab MR !1248/!1250 과정에서 Codex가 MR/pipeline 상태를 반복 polling하며 실제 플랫폼 처리시간보다 훨씬 오래 세션을 붙잡는 문제가 확인됐다.

실측 예:

- MR pipeline 약 13초
- pipeline success → auto-merge 약 0.9초
- Codex는 그보다 훨씬 오래 상태 조회 반복

Jenkins E2E에서도 동일하게 `lastBuild/api/json`, `consoleText`를 반복 조회했다.

따라서 전역 원칙:

```text
Agent does not wait by polling.
Platform waits.
```

- GitLab: Auto-merge / merge-when-pipeline-succeeds 등록 후 stop
- Jenkins/외부 job: trigger/register once 후 반복 조회 금지
- terminal failure가 관측됐을 때만 로그 1회 수집

이 원칙은 EC2 프로젝트 한정이 아니라 Codex 외부 async 작업 전반에 적용한다.

---

## 12. Batch 3 — 운영 기능 + drift + hygiene

완료된 것:

### raffle

- FE mock 고정 제거
- `winnerCount > 0` = raffle
- 기존 `POST /event-shop/purchases` 재사용
- BE 기능 변경 없이 FE adapter 정리

### provisioning

- event prizes manifest
- idempotent admin API provisioning
- duplicate name hard stop
- update는 explicit `--apply-updates`
- master admin bootstrap 분리

### env drift

- Demo AI dead DB env 제거
- Demo Back stale WORLD_* 제거

### secret scan

- MR diff scope
- base 없으면 full fallback
- Jenkins develop full tracked scan safety-net 유지

### hygiene

- Docker 264 image 제거
- 약 26GB 회수: 222GB/72% → 196GB/64%
- Registry stale package 8개 제거
- 보호 대상 유지

### Jenkins agent

- PyYAML 고정 dependency
- preflight `import yaml` fail-fast

---

## 13. 현재 위치

이제 과거 Batch를 다시 여는 단계가 아니다.

```text
현재 최적화된 host/Runner/Jenkins baseline 재측정
→ Runner lane/concurrency 실험
→ 실제 backlog soak
→ Infra handoff
```

초기 Runner 설계의 `6~8`은 역사적 가설로만 보존한다.
현재 결정값은 반드시 최신 workload로 다시 측정한다.
