# Batch 1–3 Completion Evidence

> 목적: 이미 해결된 문제를 새 세션에서 다시 TODO로 되살리지 않도록 완료 사실과 증거를 묶는다.

---

# Batch 1 — CI boundary / environment isolation

## 문제

- develop→main MR 오픈 시 Jenkins develop branch가 discovery에서 사라짐
- shared-ci가 실제 deployComponents를 비워 Demo deploy 누락
- Demo World/Production World 경계 불완전
- Demo readiness false-positive
- partial component release를 receipt로 조합하기 어려움
- Production input gate가 deploy executor 점유

## 주요 결과

MR/commit:

```text
!1233 → develop 9dada909
!1235 → main 95e034ff, develop→main NON-SQUASH
!1234 → develop 3b488680
!1236 → docs develop 699cc162
```

완료:

- multibranch discovery 정상화
- detector: validation/build/deploy/shared 축 분리
- validate-only game path에서 Unity node 제거
- receipt content-derived batchId
- Production validator exact batchId
- Production gate no-agent
- Demo World route/readiness 분리

판정:

```text
BATCH 1 = COMPLETE
```

Carry-forward:

- 다음 실제 Production promotion의 Gate G 실측

---

# CI/EC2 optimization interlude

MR:

```text
!1241 → develop 01d22492
!1242 → develop 8545c5db
```

성과:

- CI-only MR 12~15분 → 42초
- Jenkins CI-only 약 35.5초
- backend-related Jenkins 예 약 198초
- Testcontainers leak 제거
- Ryuk 복구
- stale dev runtime/network 정리
- dependency/cache 개선

이후 Batch 3 secret scan diff-scope로 MR 약 10~14초.

---

# Batch 2 — Unity Consumer-only

## 초기 계획과 폐기

초기 계획은 Jenkins Unity build automation이었다.

폐기 원인:

- Unity Personal entitlement/machine-binding 불안정
- CI infra가 Unity 인증 책임까지 떠안게 됨

폐기:

```text
Jenkins Unity Editor build
CI Unity license requirement
hostname/MAC licensing contract
```

## 최종 구조

```text
Unity 담당자 build
→ Release Bundle
→ Generic Package Registry
→ Jenkins Consumer
→ Demo validation/deployment
```

## identity correction

MR:

```text
!1243 → Unity artifact identity 3분할
```

원칙:

```text
pipelineCommit
artifactSourceCommit
unityInputId
```

## Consumer E2E chain

MR:

```text
!1248 Consumer E2E automation
!1249 E2E job JCasC registration
!1250 package-write credential binding
!1251 E2E environment bundle / Demo restore guarantee
!1252 host loopback readiness probe
!1253 agent PyYAML + preflight
```

fixture:

```text
unity-release-bundle/558d6624
```

주의:

```text
CANONICAL_PROVENANCE_PENDING
fixture only
Production 사용 금지
```

최종 Jenkins:

```text
festa-unity-bundle-e2e #4 SUCCESS
```

실제 검증:

- bundle download
- SHA/metadata cross-check
- lineage validation
- Docker local image load
- candidate WebGL intake
- Demo Game candidate
- host-loopback readiness
- WSS 101
- promote
- finally restore previous Demo

복구 target:

```text
World 367f9cdd...
WebGL 9b9c6860
```

수치:

```text
Unity Editor executions = 0
Unity License requirements = 0
Production mutations = 0
```

판정:

```text
BATCH 2 CONSUMER E2E = COMPLETE
```

---

# Batch 3 — Operational / drift / hygiene

## MR set

```text
!1244 raffle real API + env override audit
!1245 operational data provisioning + master bootstrap separation
!1246 secret scan optimization + docs
!1247 operational-data path validation-only classification
!1254 prune-images batch inspect optimization
!1255 Batch 2/3 final worklog — merged
```

## raffle

BE canonical semantics:

```text
winnerCount > 0 = raffle
winnerCount = 0 = immediate purchase
POST /api/v1/event-shop/purchases
quantity = 1 for raffle
Idempotency-Key UUID required
```

FE mock fixed.

## operational provisioning

Demo execution:

```text
run #1 unchanged=5 created=0 updated=0
run #2 unchanged=5 created=0 updated=0
```

Production execution은 Batch 3에서 하지 않음.

## env drift

Demo AI:

- dead `DATABASE_URL` / migration DB env 제거
- live/ready PASS

Demo Back:

- stale WORLD_* env duplication 제거
- runtime `WORLD_HOST=demo.ssafesta.world`
- WSS 101 PASS

## secret scan

이전:

```text
full scan ≈ 27~30초
mr-status ≈ 42초
```

현재:

```text
MR diff scan ≈ 10~14초
Jenkins develop full tracked scan 유지
```

## Jenkins agent

#521 실패 원인:

```text
ModuleNotFoundError: No module named 'yaml'
```

보정:

- linux-docker agent image에 PyYAML 고정 dependency
- preflight `python3 -c 'import yaml'`
- runtime pip install 금지

## Docker hygiene

```text
264 images deleted
222GB / 72% → 196GB / 64%
≈ 26GB reclaimed
```

잔여 component image count:

```text
Game 67
Front 45
Back 45
AI 21
```

이 숫자는 tag count / protected history / shared image 기준을 read-only로 설명할 필요가 있으나, Batch 3 종료 blocker는 아님.

## Registry hygiene

- stale/test package 8개 삭제
- state/receipt referenced package 보호
- canonical 8sha package 보호
- `558d6624` evidence package 보호

## Final state

Demo:

```text
Front 6ec32690... healthy
Back  6ec32690... healthy
AI    6ec32690... healthy
World 367f9cdd... healthy
WebGL 9b9c6860
```

Production:

```text
unchanged
CURRENT = KNOWN-GOOD = demo-approved-6169b211-20260920T091000Z
```

판정:

```text
BATCH 3 = COMPLETE
```

---

# Do not reopen without new evidence

다음은 새 evidence가 없으면 다시 설계하지 않는다.

- develop/main 역할
- exact artifact promotion
- Production state machine
- Demo/Production World isolation
- Unity Consumer-only
- raffle real API wiring
- operational provisioning 기본 구조
- AI binding ON
- secret scan split
- Docker/Registry hygiene baseline
