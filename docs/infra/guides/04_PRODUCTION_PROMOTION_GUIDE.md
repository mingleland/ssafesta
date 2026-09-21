# Production Promotion 팀 가이드

> Production은 **완전자동 배포가 아닙니다.** 현재 설계상 수동 시작 + Human Gate가 정상입니다.  
> 정확한 불변조건은 [`../canonical/05_OPERATIONS_RUNBOOK_AND_INVARIANTS.md`](../canonical/05_OPERATIONS_RUNBOOK_AND_INVARIANTS.md)를 따릅니다.

## 1. 왜 Production에서 다시 빌드하지 않나

Production의 목표는 "Demo와 같은 코드"가 아니라:

> **Demo에서 실제로 검증한 동일 artifact를 그대로 Production에서 실행하는 것**

입니다.

따라서 Production에서는:

```text
App build      0
Docker rebuild 0
Unity build    0
WebGL repack   0
Game rebuild   0
```

을 유지합니다.

---

## 2. 승격 흐름

```text
Demo runtime 검증
↓
Approved Demo Receipt
↓
develop → main NON-SQUASH
↓
Jenkins `festa-production-promotion`
↓
Receipt / main ancestry 검증
↓
Cutover Readiness Gate
↓
exact artifact candidate 배포
↓
candidate 자동 검증
↓
Public Production 활성화
↓
public 자동 검증
↓
Human Verification Gate
↓
KNOWN-GOOD 승인
```

---

## 3. Jenkins job

현재 Production 승격 job:

```text
festa-production-promotion
```

입력 파라미터:

```text
RECEIPT_ID
PRODUCTION_WORLD_HOST
```

Pipeline은 `main`을 checkout하고 해당 receipt가 main ancestry와 맞는지 검증합니다.

---

## 4. Human Gate가 두 번 있는 이유

### Cutover Readiness Gate

실제 Production 교체를 시작하기 전 승인입니다.

### Human Verification Gate

Public Production이 실제로 활성화되고 자동 검증까지 통과한 뒤, 사람이 최종 확인하고 `KNOWN-GOOD`로 승인합니다.

두 input stage는 top-level executor를 붙잡지 않도록 `agent none` 구조로 되어 있습니다.

---

## 5. 상태 모델

```text
candidate
↓
CURRENT / public
↓
Human Verification
↓
KNOWN-GOOD
```

CURRENT와 KNOWN-GOOD를 한 번에 갱신하지 않습니다.

Public 전환 뒤 문제가 발견되면 previous state를 이용한 rollback 경로가 존재합니다.

---

## 6. develop → main은 왜 NON-SQUASH인가

Approved Demo artifact의 source lineage와 main ancestry를 보존하기 위해서입니다.

```text
develop → main squash
❌ 금지

develop → main non-squash
✅ 필수
```

일반 feature/docs MR의 squash 정책과 Production promotion MR 규칙을 혼동하면 안 됩니다.

---

## 7. 개발자가 Production 때문에 하지 말아야 할 일

```text
❌ main에 직접 artifact 다시 build
❌ Production 서버에서 임의 docker image 교체
❌ WebGL 파일 수동 overwrite
❌ Unity artifact 재생성
❌ CURRENT / KNOWN-GOOD state 직접 편집
```

---

## 8. 현재 Production 상태

정본 기준:

```text
CURRENT = KNOWN-GOOD
demo-approved-6169b211-20260920T091000Z
```

현재 남은 Production 관련 항목은 다음 실제 승격 이벤트에서 처리할 carry-forward입니다.

- AI/Back stale env 정리
- Gate G 실측
- 실제 GitLab provenance Unity bundle canonical gate
