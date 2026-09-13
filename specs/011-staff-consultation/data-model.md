# Phase 1 Data Model: 직원 / 사람 상담

**Date**: 2026-09-13 | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

**네 테이블이 전부 `V1__initial_schema.sql`에 이미 있다.** 신규 테이블은 없고, P1이 필요로 하는 것은 컬럼 둘과 제약 하나다.

---

## 기존 스키마 (V1)

### `booth_staffs` — 부스 직원

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `booth_id`, `user_id` | BIGINT | 복합 PK |
| `role` | VARCHAR(30) | 어휘가 정해져 있지 않았다 — 아래 참조 |
| `joined_at` | TIMESTAMPTZ | |

spec 005가 이 테이블을 **읽기만** 한다(`BoothAccessGuard`). 엔티티 javadoc이 "행을 만드는 것은 011의 몫"이라고 이미 적어 뒀으므로, 행을 만들어도 005 코드는 그대로 동작한다.

### `staff_invitations` — 직원 초대

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT identity | |
| `booth_id`, `invited_user_id`, `invited_by_user_id` | BIGINT | |
| `role` | VARCHAR(30) | |
| `status` | VARCHAR(20) | 기본 `PENDING` |
| `expires_at` | TIMESTAMPTZ | **C-07 48시간을 담을 자리가 이미 있다** |
| `accepted_at`, `created_at` | TIMESTAMPTZ | |

`ux_staff_invitations_pending (booth_id, invited_user_id) WHERE status='PENDING'` — 같은 사람에게 대기 중 초대가 둘 생기지 않는다.

### `consultations` — 요청 겸 세션 (R-06)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT identity | `requestId` = `sessionId` = 이 값의 문자열 표현 |
| `booth_id` | BIGINT NOT NULL | |
| `visitor_user_id` | BIGINT | 게스트 불가(FR-014)이므로 P1에서는 항상 채워진다 |
| `agent_id` | BIGINT | AI 직원 참조 |
| `staff_user_id` | BIGINT | 수락 전 `NULL` |
| `ai_conversation_id` | VARCHAR(100) | 요약 조달 키 |
| `summary` | TEXT | **Handoff Summary 스냅샷.** 없으면 `NULL`(R-02) |
| `status` | VARCHAR(20) | 기본 `REQUESTED` |
| `requested_at`, `accepted_at`, `ended_at` | TIMESTAMPTZ | |

### `consultation_messages` — **P2**

V1에 있지만 **P1에서 쓰지 않는다**(C-12 — 실시간 메시지 송수신·저장은 P2). 건드리지 않는다.

---

## V31 마이그레이션 — 더할 것

> develop 최신은 `V30__ai_documents_replacement.sql`이다.

```sql
-- 1) 직원 상담 상태 (FR-004, R-04). 기본은 OFFLINE — US2가 "오프라인이 기본 상태"로 못박았다
ALTER TABLE booth_staffs
  ADD COLUMN consultation_status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE';
ALTER TABLE booth_staffs
  ADD CONSTRAINT ck_booth_staffs_consultation_status
  CHECK (consultation_status IN ('AVAILABLE','BUSY','AWAY','OFFLINE'));

-- 2) 역할 어휘 고정 (FR-002)
ALTER TABLE booth_staffs
  ADD CONSTRAINT ck_booth_staffs_role
  CHECK (role IN ('ADMIN','CONTENT_EDITOR','CONSULTANT'));
ALTER TABLE staff_invitations
  ADD CONSTRAINT ck_staff_invitations_role
  CHECK (role IN ('ADMIN','CONTENT_EDITOR','CONSULTANT'));

-- 3) 직원당 활성 상담 1건 (SC-006 / FR-021) — DB가 강제한다 (R-03)
CREATE UNIQUE INDEX ux_consultations_active_staff
  ON consultations (staff_user_id) WHERE status = 'ACCEPTED';

-- 4) 대기열 조회 경로
CREATE INDEX ix_consultations_booth_status ON consultations (booth_id, status);

-- 5) 상태 어휘 고정
ALTER TABLE consultations
  ADD CONSTRAINT ck_consultations_status
  CHECK (status IN ('REQUESTED','ACCEPTED','ENDED','EXPIRED','REJECTED'));
ALTER TABLE staff_invitations
  ADD CONSTRAINT ck_staff_invitations_status
  CHECK (status IN ('PENDING','ACCEPTED','CANCELLED','EXPIRED'));
```

**기존 행 호환**: 네 테이블 모두 develop에 쓰는 코드가 없어 운영 데이터가 0건이다. `NOT NULL DEFAULT`와 `CHECK` 추가가 기존 행을 깨뜨릴 여지가 없다.

---

## 상태 전이

### Consultation

```text
REQUESTED ──accept(한 명만, R-03)──> ACCEPTED ──end(양쪽 누구나)──> ENDED
    │                                    │
    ├──cancel(방문자)──> ENDED            └──lease 만료 유예 후──> ENDED (D03)
    └──10분 경과(sweeper)──> EXPIRED
```

- `REJECTED`는 어휘에 두되 P1에서 만들지 않는다 — 확정 계약에 거절 경로가 없다(Owner 취소만 있는 초대와 같은 판단).
- 만료된 요청은 수락할 수 없다. 조건부 갱신의 `WHERE status='REQUESTED'`가 그것을 보장한다.

### Staff Invitation

```text
PENDING ──accept(본인)──> ACCEPTED  (booth_staffs 행 생성)
   │
   ├──cancel(Owner, FR-017)──> CANCELLED
   └──48시간 경과(sweeper, C-07)──> EXPIRED
```

초대받은 사용자의 **거절 API는 없다**(C-10). 수락하지 않으면 48시간 뒤 만료된다.

### Staff Presence

```text
OFFLINE(기본) <──> AVAILABLE <──> AWAY
                       │
                       └──> BUSY   (수락 시 서버가 전환, 종료 시 복귀)
```

`BUSY`는 **서버가 관리한다** — 직원이 임의로 설정하는 값이 아니라 활성 상담의 결과다. 나머지 셋은 명시적 REST로 바꾼다(R-04).

---

## 권한 매트릭스 (FR-002·FR-003, C-09)

| 행위 | Owner | `ADMIN` | `CONTENT_EDITOR` | `CONSULTANT` |
|---|---|---|---|---|
| Lease 결제·취소·양도 | ✅ | ❌ | ❌ | ❌ |
| 직원 초대·역할 변경·삭제 | ✅ | ✅ | ❌ | ❌ |
| Layout·Facade 편집 | ✅ | ✅ | ✅ | ❌ |
| 상담 수락·종료 | ✅ | ✅ | ✅ | ✅ |
| 직원 목록 조회 | ✅ | ✅ | ✅ | ✅ |

**Owner는 `booth_staffs` 행이 아니다**(FR-018). 목록 응답에서만 역할 `OWNER`의 읽기 전용 행으로 합성하며 변경·삭제 대상이 아니다.

### ⚠️ 행을 만드는 순간 `CONSULTANT` 가 편집 권한을 얻는다

`BoothAccessGuard.requireEditor` 는 **역할을 보지 않는다** — `booth.isOwnedBy(userId) || staffs.existsByBoothIdAndUserId(...)`, 즉 **행이 있으면 통과**다. 지금까지 이 테이블에 행을 만드는 코드가 없어 드러나지 않았을 뿐이다.

`requireEditor` 를 쓰는 경로는 Layout·Facade만이 아니다.

| 호출처 | 무엇을 연다 |
|---|---|
| `booth/BoothLayoutService`(2곳)·`BoothLayoutQueryService` | Layout Draft 저장·공개·조회 |
| `ai/AiAgentService`(2곳) | AI 직원 생성·수정 |
| `ai/AiDocumentService` | 부스 문서 |
| `survey/SurveyService`·`SurveyResultService` | 설문 생성·결과 조회 |
| `project/ProjectService` | 프로젝트 편집 |

**따라서 `-136` 의 첫 작업은 초대 API가 아니라 `requireEditor` 에 역할을 태우는 일이다.** 순서가 뒤집히면 초대 기능을 여는 순간 `CONSULTANT` 가 남의 부스 문서와 설문 결과까지 만지게 된다. C-09가 명시한 것은 Booth Studio 편집이지만, 같은 게이트가 AI·설문·프로젝트도 지키고 있으므로 **역할 게이트는 `requireEditor` 한 자리에서 갈라야 한다**(GitLab #116이 "009 프로젝트 API는 `BoothEditorGuard` 그대로 간다"로 통보한 것과도 맞는다 — 게이트는 하나로 두고 그 안에서 역할을 본다).

**FR-003(Lease는 Owner만)은 구조적으로 이미 지켜진다.** `BoothLeaseService.lease(userId, …)` 가 행위자 본인의 부스(`ownBooth(userId)`)에만 작용해 남의 임대를 건드릴 경로 자체가 없다. 직원 행이 생겨도 달라지지 않는다.
