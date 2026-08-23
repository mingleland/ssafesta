# Tasks: 아바타 저장 API (013a BE분)

**Input**: `specs/013-avatar-customization/BE/` — [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [quickstart.md](quickstart.md) / 공동 정본 [spec.md](../spec.md) / 계약 [contracts/avatar-profile-api.md](../contracts/avatar-profile-api.md)

**Tests**: 포함한다. plan.md §Source Code가 `MyAccountAvatarApiIntegrationTest`를 산출물로 명시했고, quickstart §3이 시나리오 9종을 고정했다. 003·004·005와 같은 Testcontainers 패턴이다.

**Organization**: spec의 사용자 스토리 기준. **US2(외형 전파)는 BE 몫이 0이라 Phase를 만들지 않는다** — Phase 5에 근거를 남긴다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능 (다른 파일, 미완 작업에 의존하지 않음)
- **[Story]**: US1 / US3 (spec.md 사용자 스토리)

## Path Conventions

Web API 단일 모듈 — `backend/src/main/java/com/example/ssafesta/`, `backend/src/test/java/com/example/ssafesta/`

---

## Phase 1: Setup

**Purpose**: 전제 확인. 이 기능은 **신규 스키마·신규 의존성이 0**이라 초기화 작업이 없다.

- [ ] T001 회귀 기준선 확보 — `cd backend && ./mvnw test`가 green인지 확인하고 통과 수를 기록한다 (구현 후 대조용). **기준선 217 passed / 0 failed** (2026-08-24 실측)
- [ ] T002 [P] 전제 실측 재확인 — `users.avatar_code`가 `TEXT`인지(`backend/src/main/resources/db/migration/V10__avatar_code_text.sql`), `User.java:27-28` 매핑이 `columnDefinition = "text"`인지 확인한다. **신규 마이그레이션을 만들지 않는다**(data-model.md §1). 컬럼이 `VARCHAR`면 즉시 중단하고 헌법 23조·T-24를 근거로 보고한다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1(쓰기)과 US3(읽기)이 **같은 파일**(`User.java`)을 건드린다. 먼저 끝내야 두 스토리가 병렬로 갈 수 있다.

- [ ] T003 `backend/src/main/java/com/example/ssafesta/user/User.java`에 `getAvatarCode()`와 `changeAvatarCode(String avatarCode)`를 추가한다. **값을 변형하지 않는다** — trim·대소문자·정규화 금지(data-model.md §2 왕복 무손실 불변식, research.md R-05). `changeNickname`(`:58`)의 형태를 따른다

**Checkpoint**: 엔티티가 avatarCode를 읽고 쓸 수 있다. US1·US3 병렬 착수 가능.

---

## Phase 3: User Story 1 — 외형을 고르고 저장한다 (Priority: P0)

**Goal**: 회원이 `PUT /api/v1/users/me/avatar`로 인코딩 문자열을 저장하고, 서버가 저장한 그대로 돌려준다. 거부는 **사유가 보이는** 400으로 나간다(FR-012·SC-005).

**Independent Test**: 회원 토큰으로 PUT → 200 + 요청과 동일한 `avatarCode` echo. DB `users.avatar_code`에 같은 바이트열이 들어간다. US3 없이도 검증된다.

### Tests for User Story 1 ⚠️ 먼저 작성하고 **실패를 확인**한 뒤 구현한다

- [ ] T004 [P] [US1] `backend/src/test/java/com/example/ssafesta/user/MyAccountAvatarApiIntegrationTest.java` 신설 — 저장 성공 경로: `PUT` 200 + echo 일치 + DB 왕복 **바이트 동일**. 케이스 2종: 프리셋형 `sk_01`, 모듈러 실측형 `fa|3=SK_Hair_Long_01|c=FF8800` (파이프·언더스코어·대문자가 게이트에 걸리면 안 된다 — T-24 회귀 방지)
- [ ] T005 [P] [US1] 같은 파일에 거부 경로 — blank / 3801자 / 제어문자(`\n`) 각각 `400 VALIDATION_FAILED`. 봉투가 `errors[0] = { rule: "FIELD_INVALID", field: "avatarCode", message: … }`이고 **세 message가 서로 다른지** 검증한다(무엇이 틀렸는지 구분되어야 한다)
- [ ] T006 [P] [US1] 같은 파일에 경계·권한 — 정확히 3800자 **통과**(research.md R-04, 하향 금지), 게스트 토큰 `403 MEMBER_ONLY`, 미인증 `401`

### Implementation for User Story 1

- [ ] T007 [US1] `backend/src/main/java/com/example/ssafesta/user/AvatarCodePolicy.java` 신설 — `@Component`, `validate(String)`. data-model.md §3의 3규칙을 **그 순서대로**(blank → 길이 3800 → 인쇄 가능 ASCII `0x20`–`0x7E`) 검사하고 첫 위반의 사유 문장으로 `ApiException`을 던진다. 같은 패키지 `NicknamePolicy.java`의 구조를 따른다(research.md R-06)
- [ ] T008 [US1] `MyAccountController.java`에 `@PutMapping("/avatar")` + `@Transactional` 추가 — `MemberPrincipal.requireMemberId`로 게스트 차단(R-08), `AvatarCodePolicy.validate` 후 `user.changeAvatarCode(...)`, 응답은 저장값 echo. 요청 `record AvatarChangeRequest(String avatarCode)`, 응답 `record AvatarResponse(String avatarCode)`를 컨트롤러 내부 record로 둔다(`MyAccountResponse:79` 관례)
- [ ] T009 [US1] 검증 실패가 `400 VALIDATION_FAILED` + `FIELD_INVALID`/`field:"avatarCode"` 봉투로 나가는지 확인한다 — `ApiErrorDetail.field(name, message)`(T058) 재사용. **새 `ErrorCode`·새 `rule`을 만들지 않는다**(research.md R-07). 필요하면 `GlobalExceptionHandler` 경유 경로만 확인하고 핸들러 자체는 수정하지 않는다

**Checkpoint**: T004~T006 green. 저장이 독립적으로 동작한다.

---

## Phase 4: User Story 3 — 다음에 들어와도 그 모습이다 (Priority: P0)

**Goal**: `GET /api/v1/users/me` 응답에 `avatarCode`가 실려 재접속 복원(FR-013·SC-002)이 가능해진다. 게스트는 영속 저장되지 않는다(FR-015·헌법 12조).

**Independent Test**: DB에 값을 직접 넣고 `GET /users/me` 호출 → 그 값이 그대로 온다. 저장 안 한 신규 회원은 `null`. US1 없이도 검증된다.

### Tests for User Story 3 ⚠️ 먼저 작성하고 실패를 확인한다

- [ ] T010 [P] [US3] `MyAccountAvatarApiIntegrationTest.java`에 복원 경로 추가 — 신규 회원 `GET /users/me` → `avatarCode: null`(**서버가 기본값을 만들어 넣지 않는다** — data-model.md §2), 저장된 회원 → 저장값 그대로, 재저장(값 → 다른 값) 후 최신값
- [ ] T011 [P] [US3] `MyAccountApiIntegrationTest`(기존, 있으면) 또는 T010에 회귀 확인 — 닉네임 변경·탈퇴 응답이 `avatarCode` 필드 추가로 깨지지 않는지. 가산적 변경이라 기존 필드는 그대로여야 한다

### Implementation for User Story 3

- [ ] T012 [US3] `MyAccountController.java:79`의 `record MyAccountResponse`에 `String avatarCode`를 마지막 필드로 추가한다
- [ ] T013 [US3] `MyAccountResponse`를 만드는 **두 지점을 모두** 갱신한다 — `me()`(`:41`)와 `changeNickname()`(`:55`). 한 곳만 고치면 닉네임 변경 응답에서 avatarCode가 조용히 사라진다

**Checkpoint**: T010~T011 green. 복원이 독립적으로 동작한다. US1과 합치면 저장→재접속 전체 경로 완성.

---

## Phase 5: User Story 2 — 남들에게도 그 모습으로 보인다 (Priority: P0) — **BE 몫 없음**

작업을 만들지 않는다. US2는 **Unity Dedicated Server의 실시간 외형 전파**이고(헌법 2·5조 — 실시간 상태는 게임 서버 권위), 네트워크 payload는 `INetworkSerializable` 고정 크기 struct다. Spring은 이 경로에 관여하지 않는다.

spec FR-011("서버는 ID가 카탈로그 범위 내인지 검증")의 "서버"도 Unity 서버를 가리킨다 — Spring 경로의 항목 검증은 C-06 리드 확정이 **spec 012(상점)로 미뤘고**, 계약이 *"상점 도입 시 이 지점에 추가"*로 자리만 잡아뒀다(research.md R-05).

---

## Phase 6: Polish & 통보 (헌법 24·29조)

**Purpose**: 계약 문서 정합과 소비자 통보. **이 Phase를 건너뛰면 이번 건 자체가 반복된다** — 013a가 "계약 확정 + 컬럼 완료 + 엔드포인트 없음"으로 방치된 원인이 통보·문서 갱신 누락이었다.

- [ ] T014 회귀 전체 — `cd backend && ./mvnw test`. T001 기준선 217보다 늘고 기존 테스트가 하나도 깨지지 않았는지 확인한다
- [ ] T015 [P] `specs/013-avatar-customization/contracts/avatar-profile-api.md` 갱신 — 상태줄 `⚠️ 제안이다` → 구현 완료. **확정값 기입**: `PUT` 채택(R-01), `GET /users/me` 포함 분기 채택·별도 GET 미구현(R-02), 길이 상한 3800(R-04), 문자셋 인쇄 가능 ASCII(R-03)
- [ ] T016 [P] `docs/08_Backend_API_명세서.md` §2(Auth/User)에 `PUT /users/me/avatar`와 `GET /users/me` 응답의 `avatarCode`를 추가한다 — **지금 docs/08에 아바타 절이 아예 없다**
- [ ] T017 Unity 파트 통보(헌법 24조) — ① `festa-unity/.../Integration/Mock/MockUserApiClient.cs:23`의 `PATCH` 주석을 `PUT`으로 정정 요청(#24 확정 이전 POC C 시점 값이다), ② 문자셋 `0x20`–`0x7E` 확인 요청(R-03 — 생산자에 닿는 값이라 확인받는다). 가산적 변경이라 Breaking은 아니다
- [ ] T018 [P] FE 파트 통보 — `GET /users/me` 응답에 `avatarCode?: string | null` 추가(가산적)
- [ ] T019 [P] `docs/HDD/작업일지.md`에 기록(헌법 29조). 문제가 있었으면 `docs/HDD/트러블슈팅.md`에 T-번호로 등록 — **해결 못 했어도 등록한다**
- [ ] T020 `quickstart.md` §2 수동 확인 절차를 1회 실행하고 §3 매핑 9종이 전부 커버됐는지 대조한다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 의존 없음
- **Phase 2 (Foundational)**: Phase 1 이후. **US1·US3 둘 다 차단**한다(같은 `User.java`)
- **Phase 3 (US1) · Phase 4 (US3)**: Phase 2 이후 **병렬 가능**. 서로 의존하지 않는다
- **Phase 6 (Polish)**: Phase 3·4 완료 후

### User Story Dependencies

- **US1 (P0)**: Phase 2 후 착수. 다른 스토리에 의존하지 않는다
- **US3 (P0)**: Phase 2 후 착수. US1에 의존하지 않는다 — 테스트가 DB에 값을 직접 넣어 검증한다
- **US2 (P0)**: BE 작업 없음 (Phase 5)

### Within Each User Story

- 테스트를 먼저 쓰고 **실패를 확인한 뒤** 구현한다
- 엔티티(Phase 2) → 정책 → 엔드포인트
- 한 파일을 건드리는 작업은 [P]를 붙이지 않는다 — `MyAccountController.java`는 T008·T012·T013이 공유하므로 순차다

### Parallel Opportunities

- T004·T005·T006은 같은 테스트 파일이라 **한 사람이 순차로** 쓰는 편이 낫다. [P]는 "서로 의존하지 않음"을 뜻하며 파일 충돌은 별개다
- **US1(Phase 3)과 US3(Phase 4)은 사람이 둘이면 진짜 병렬**이다 — 단 `MyAccountController.java`를 함께 건드리므로 T008과 T012·T013 사이에 조율이 필요하다
- T015~T019(문서·통보)는 서로 다른 파일이라 전부 병렬

---

## Parallel Example: Phase 6

```text
T015 (contracts/avatar-profile-api.md)  ─┐
T016 (docs/08 §2)                        ├─ 동시 진행 가능 (파일이 전부 다르다)
T018 (FE 통보)                            │
T019 (작업일지)                           ─┘
```

---

## Implementation Strategy

### MVP

**US1 + US3이 함께 MVP다.** 저장만 있고 복원이 없으면 사용자에게 아무 변화가 없고(재접속하면 기본 프리셋), 복원만 있고 저장이 없으면 넣을 값이 없다. 둘이 합쳐 FR-013 하나를 이룬다.

Phase 1~4 = 12개 작업이 최소 배포 단위다. Phase 6은 머지 전 필수(헌법 24·29조).

### 증분 순서

1. Phase 1~2 (T001~T003) — 전제 확인 + 엔티티
2. Phase 3 (T004~T009) — 저장. 여기서 `PUT`이 독립 동작
3. Phase 4 (T010~T013) — 복원. 여기서 Unity가 Mock을 실제 구현으로 바꿀 수 있다
4. Phase 6 (T014~T020) — 회귀·문서·통보

### 밟지 말아야 할 지뢰

- **값을 변형하지 마라.** trim·정규화·기본값 대체 전부 금지(R-05). facade 팔레트(T059)가 대문자 정규화를 한 것은 계약이 팔레트 소속 검증을 **명령했기 때문**이고, 여기는 반대 명령("파싱하지 않는다")이다
- **길이 상한을 낮추지 마라.** 3800은 Unity `AvatarAppearance.MaxEncodedLength`가 소유한 값이다. 낮추면 모듈러 형식(`fa|…`, 파츠 이름이 그대로 들어가 길다)이 거부된다 — 헌법 23조·T-24
- **실패를 조용히 삼키지 마라.** 거부는 사유 문장이 보이는 400이다(FR-012·SC-005). T-24가 정확히 "무반응"이었다
- **`MyAccountResponse` 생성 지점이 둘이다**(T013). 하나만 고치면 닉네임 변경 응답에서만 필드가 사라진다
