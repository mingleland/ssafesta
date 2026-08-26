# BE Tasks: World Session 발급 API (002 BE분)

**Input**: [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [quickstart.md](quickstart.md) + `specs/infra-003-unity-server-deploy/contracts/` (origin/develop 정본)

**Shared spec**: [../spec.md](../spec.md) | **Unity tasks**: [../tasks.md](../tasks.md) (T001~T019, 별개 번호 공간)

> **번호 공간이 파트별로 분리돼 있다** (#43). 이 파일의 `T0xx`는 BE 몫이고 루트 `tasks.md`의 `T0xx`는 Unity 몫이다. 013a의 `BE/`·`Unity/`와 같은 구조다.
> **파일 경로는 infra-003 tasks가 지정한 경로를 그대로 쓴다** — T015→`WorldSessionApiIntegrationTest`, T019→`WorldSessionController`/`Service`, T020→`WorldEntryTokenIssuer`, T021→`application-*.yml`, T040→`WorldEntryTokenIssuerTest`. 경로를 바꾸면 같은 클래스가 두 번 구현된다.

**Tests**: 포함한다. spec 002 SC-004("유효하지 않은 토큰으로 접속 성공 0건")가 자동 검증을 요구하고, infra-003이 테스트 파일 2개를 이미 지정했다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이라 병렬 가능
- **[US2]**: spec 002 User Story 2 — "접속 주소를 서버에서 받는다" (P0). US1은 구현됨(Unity), US3은 Unity·FE 몫

---

## Phase 1: Setup

**Purpose**: 설정 경계와 실패 조건을 먼저 고정한다 — 잘못된 값으로 기동하면 첫 접속에서야 발견된다.

- [X] T001 `app.world.*` record와 기동 검증(scheme ∈ {ws,wss} · port 1~65535 · secret Base64 디코드 ≥32바이트, 위반 시 기동 실패)을 `backend/src/main/java/com/example/ssafesta/world/WorldProperties.java`에 작성한다
- [X] T002 `app.world` 블록(local 값 `ws`/`127.0.0.1`/`7777` + `connection-token-secret: ${CONNECTION_TOKEN_SECRET}` — 기본값 없이 `jwt-secret`과 같은 패턴)을 `backend/src/main/resources/application-local.yml`에 추가한다
- [X] T003 [P] 실행 환경에 export 하는 애플리케이션 Secret 이름(`JWT_SECRET`·`CONNECTION_TOKEN_SECRET`, 값은 비움)을 `backend/.env.example`에 추가한다 (헌법 15조 — 이름만)
  - ⚠️ **로컬에만 반영됐다.** `backend/.env.example`이 `backend/.gitignore:3`에 등재돼 있어 커밋되지 않는다(`.env` 바로 아래 줄 — 의도인지 실수인지 불명). 그래서 팀에 전달되는 Secret 문서는 **추적되는 파일 쪽**이다 — [quickstart.md](quickstart.md) §1과 `bruno/06-world-session` 요청 docs. `.env.example`을 추적 대상으로 되돌리는 건 저장소 전체 결정이라 이 MR에서 건드리지 않는다

**Checkpoint**: 설정이 틀리면 기동이 실패한다. 조용히 기본 키로 서명하는 경로가 없다.

---

## Phase 2: Foundational (Blocking)

**Purpose**: 토큰 발급기. endpoint보다 먼저 서야 하고, **Access Token 키와 분리**되는 지점이라 이 기능의 보안 경계 그 자체다.

- [X] T004 전용 HS256 `JwtEncoder`(생성자에서 `CONNECTION_TOKEN_SECRET`으로 조립 — `JwtConfiguration`의 AT 빈을 재사용하지 않는다)와 120초 claims(`jti·sub·role·playerId·nickname·avatarCode·sessionId·worldId·channelId·iat·exp`, `iss: ssafesta-backend`, `aud: ssafesta-world`) 발급을 `backend/src/main/java/com/example/ssafesta/world/WorldEntryTokenIssuer.java`에 구현한다

**Checkpoint**: 토큰 하나를 만들 수 있다. 아직 아무도 호출하지 않는다.

---

## Phase 3: User Story 2 — 접속 주소를 서버에서 받는다 (P0) 🎯 MVP

**Goal**: 회원·게스트가 AT로 `POST /api/v1/world-sessions`를 호출해 구조화 endpoint와 1회용 토큰을 받는다. Unity가 Mock을 끄고 실제 경로로 붙을 수 있게 된다 (FR-005·FR-006·FR-012).

**Independent Test**: `POST /api/v1/world-sessions`를 회원·게스트 토큰으로 각각 호출해 응답이 계약 스키마와 일치하고, 토큰 claims가 DB의 닉네임·아바타와 같고, `worldId:"1F"`는 400으로 거부되는지 확인한다 (quickstart §2·§3).

### Tests for User Story 2 ⚠️ (구현 전에 작성하고 실패를 확인한다)

- [X] T005 [P] [US2] claims 전량·`exp-iat==120s`·`jti` 유일성·`iss`/`aud` 고정값·**`JWT_SECRET`으로는 서명 검증이 실패함**(키 분리 증명)을 검증하는 테스트를 `backend/src/test/java/com/example/ssafesta/world/WorldEntryTokenIssuerTest.java`에 작성한다
- [X] T006 [P] [US2] 회원 200·게스트 200(`role=GUEST`·파생 닉네임·`avatarCode=null`)·미인증 401·정지 계정 403·`worldId` 유효/무효(200 / 400 `FIELD_INVALID` `field:"worldId"`)·응답 스키마 전 필드·`expiresAt==exp`를 검증하는 통합 테스트를 `backend/src/test/java/com/example/ssafesta/world/WorldSessionApiIntegrationTest.java`에 작성한다

### Implementation for User Story 2

- [X] T007 [US2] 신원 해석(회원은 `users` 행에서 닉네임·아바타·상태, 게스트는 AT subject에서 파생)과 `sessionId` 생성·응답 조립을 `backend/src/main/java/com/example/ssafesta/world/WorldSessionService.java`에 구현한다 (T004 의존)
- [X] T008 [US2] `POST /api/v1/world-sessions` 핸들러와 요청·응답 record, `worldId` 검증(`11F`만 통과, 위반 시 `VALIDATION_FAILED`+`FIELD_INVALID`)을 `backend/src/main/java/com/example/ssafesta/world/WorldSessionController.java`에 구현한다 (T007 의존)
- [X] T009 [US2] 발급 로그를 `sessionId`·role·(회원이면 userId)까지만 남기고 토큰 원문·Secret·닉네임을 남기지 않도록 `backend/src/main/java/com/example/ssafesta/world/` 전체를 점검한다 (FR-023, data-model 불변식 3)

**Checkpoint**: US2가 독립적으로 동작한다. Unity `ApiConfig.useMock=0` 전환의 BE 전제가 충족된다.

---

## Phase 4: Polish & 인수인계

- [X] T010 [P] `월드 세션 발급.bru`(성공·`worldId` 무효·미인증 3케이스)를 `backend/bruno/06-world-session/`에 추가한다
- [X] T011 `cd backend && ./mvnw test` 전체 회귀 — 기존 기준선(2026-08-24 실측 217 passed)에 신규 2개 파일이 더해져 green
- [X] T012 `/v3/api-docs`에 `POST /api/v1/world-sessions`가 스키마와 함께 노출되는지 확인한다 (Jira -84 완료 조건 ③)
- [X] T013 [P] `docs/HDD/작업일지.md`에 2026-08-25 항목을 쓰고, 문제 발생 시 `docs/HDD/트러블슈팅.md`에 T-번호로 등록한다 (헌법 29조)
- [X] T014 통보 4건 초안을 작성한다 — Jira -84 제목 `GET`→`POST` · Jira -170 완료 조건 축소 · Infra에 OpenAPI `worldId` 추가 요청 · Unity에 게스트 닉네임 규칙·`avatarCode` claim 크기 실측 (quickstart §4). **게시는 승인 후** (헌법 24조)

---

## Dependencies & Execution Order

```text
T001 ──▶ T002 ──▶ T004 ──▶ T007 ──▶ T008 ──▶ T009 ──▶ T011 ──▶ T012
  └─ T003 [P]        ▲        ▲                          ▲
                     │        │                          │
              T005 [P] ───────┘                    T010 [P]
              T006 [P] ────────────────────────────┘
                                                   T013 [P] · T014
```

- **Phase 1 → 2 → 3 순서**는 강제다. `WorldProperties`(T001) 없이는 발급기가 키를 못 얻고, 발급기(T004) 없이는 service가 응답을 못 만든다.
- **T005·T006은 구현 전에 작성**하고 실패를 확인한다. T005는 T004만, T006은 T008까지 있어야 green이 된다.
- **T009는 T008 이후**에만 의미가 있다(점검 대상이 그때 완성된다).
- **[P] 묶음**: (T003) · (T005, T006) · (T010, T013)

## Parallel Example

```bash
# 테스트 2개를 먼저 나란히 작성 (다른 파일, 서로 무관)
Task: "T005 WorldEntryTokenIssuerTest — claims·TTL·키 분리"
Task: "T006 WorldSessionApiIntegrationTest — 권한·스키마·worldId"
```

## Implementation Strategy

**MVP = Phase 1 + 2 + 3 (T001~T009).** 9개 작업이 최소 배포 단위다. US2 하나가 spec 002의 BE 몫 전부라 쪼갤 증분이 없다 — endpoint가 없으면 Unity는 Mock에 머물고, 있으면 그 즉시 전환 가능하다.

Phase 4는 머지 전 필수(헌법 24·29조). T014는 **초안까지만** — 게시는 승인 후.

## 밟지 말아야 할 지뢰

- **`JwtConfiguration`의 `JwtEncoder`를 주입받지 마라.** HS512 + `JWT_SECRET`이라 게임 서버에 그 키를 넘기는 순간 Access Token 위조가 가능해진다. T005의 키 분리 테스트가 이 실수를 잡는다 (R-04)
- **세션을 저장하지 마라.** 테이블·Redis 키·정리 스케줄러 전부 만들지 않는다. 재사용 차단 원장은 게임 서버 소유이고, BE가 조회 지점을 만들면 헌법 14조("Spring 장애가 월드 입장을 막지 않는다")를 깬다 (R-06)
- **`worldId` 파라미터를 지우지 마라.** 값이 `11F` 하나뿐이어도 자리는 남긴다 — 지우는 것이 계약 변경이다 (헌법 9조, #31)
- **claims에 클라이언트 입력을 싣지 마라.** 닉네임·아바타는 `users` 행에서, 게스트 신원은 AT subject에서만 유도한다 (헌법 16조)
- **`docs/08` §16을 고치지 마라.** infra-003 T070이 소유한다 — 중복 편집이 #59가 지적한 복제 구조를 만든다

## Summary

- Total: 14 (Setup 3 · Foundational 1 · US2 5 · Polish 5)
- Completed: **14 / Remaining: 0**
- MVP: T001~T009 — 완료
- Tests: 2 파일 16건 (T005 7 · T006 9) — infra-003 T015·T040과 동일 경로
- 신규 마이그레이션 0 · 신규 오류 코드 0 · `SecurityConfiguration` 변경 0

### 검증 결과 (2026-08-25)

| 대상 | 결과 |
|---|---|
| `./mvnw test` 전체 | ✅ **247 passed / 0 failed** — BUILD SUCCESS (기준선 217 + 그 후 반입분 + 신규 16) |
| `WorldEntryTokenIssuerTest` (7건) | ✅ claims 전량·TTL 120s·`jti` 유일성·**키 분리**·짧은 secret 기동 실패 |
| `WorldSessionApiIntegrationTest` (9건) | ✅ 회원·게스트·401·403·`worldId` 유효/무효·스키마·`expiresAt==exp` |
| 앱 기동 (`spring-boot:run`, local) | ✅ 신규 필수 property로 정상 기동 — 6.8s |
| `/v3/api-docs` | ✅ `/api/v1/world-sessions` `post` + 스키마 3종(`WorldSessionRequest`·`WorldEndpoint`·`WorldSessionResponse`) 노출 |
| 실환경 스모크 (curl) | ✅ 게스트 발급 200 — `alg HS256`·`iss/aud` 고정·`role GUEST`·`nickname 게스트-40cc`·`avatarCode` 부재·**TTL 정확히 120초** / `worldId:"1F"` 400 `FIELD_INVALID` / 미인증 401 |

**하네스 함정 1건**: Git Bash에서 `openssl rand -base64`가 CRLF를 붙여 secret에 `\r`가 섞이면 `Illegal base64 character d`(0xd = CR)로 **`JwtConfiguration`이 먼저** 터진다. `tr -d '\r\n'`으로 잘라야 한다 — 코드 결함이 아니다.
