# Implementation Plan: World Session 발급 API (002 BE분)

**Branch**: `feature/S15P21A604-84-world-session` (예정) | **Date**: 2026-08-25 | **Spec**: [spec.md](../spec.md)

**Input**: `specs/002-world-session/spec.md` (확정, 2026-08-12) + **infra-003 contracts** (origin/develop: `world-session.openapi.yaml`·`world-entry-token.md`·`game-runtime.md`, 2026-08-24 Infra 리뷰 완료) + GitLab #31 층 파라미터 결정 + Jira `S15P21A604-84`·`-170`

> 루트 [plan.md](../plan.md)는 Unity 파트 소유(2026-08-12)이며 그대로 둔다. 이 문서는 #43 규칙의 BE 실행 산출물이다.
> 루트 plan이 예고한 "BE 증분은 별도 plan"이 바로 이것이다.

## Summary

spec 002에서 BE 몫으로 남은 유일한 것 — **`POST /api/v1/world-sessions`** — 를 구현한다. 회원·게스트가 Access Token으로 호출하면 구조화 endpoint(`scheme/host/port`)와 **120초 1회용 connection token**을 받고, Unity는 그 값으로만 접속한다(헌법 8조). 이 API가 서면 Unity `MockWorldSessionClient` → 실제 전환(002 T003~T004)과 wss 종단 실측(#52)의 BE 전제가 모두 풀린다.

설계의 중심은 세 가지다.

1. **BE는 발급만 하고 검증에서 손을 뗀다.** 게임 서버가 서명을 자체 검증하고(헌법 14조) `jti` 소비 원장을 소유한다(FR-007·013·014). 그래서 **세션 저장소가 없다** — 테이블 0, Redis 키 0, 스케줄러 0. Spring이 죽어도 이미 발급된 토큰으로 월드에 들어간다(헌법 3조 정신).
2. **서명 키를 Access Token과 분리한다.** 전용 `CONNECTION_TOKEN_SECRET`(HS256)만 게임 서버와 공유한다. `JWT_SECRET`(HS512)을 재사용하면 게임 서버 컨테이너가 AT 위조 능력을 갖게 된다 — 계약(`world-entry-token.md`)이 이미 이를 막는 방향으로 확정됐고, 키 분리는 테스트로 고정한다(quickstart §3-8).
3. **층 파라미터 자리를 지킨다**(헌법 9조, FR-012, #31). `worldId`는 optional이고 값은 `11F`만 유효 — 고정값이지만 파라미터를 지우는 것이 곧 계약 변경이라 남긴다.

결정 근거 전체: [research.md](research.md) R-01~R-10.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Security (Resource Server), Nimbus(JJWT 아님 — 기존 `NimbusJwtEncoder` 계열 재사용하되 **새 인스턴스·새 키**), Jackson. 재사용: `ApiErrorWriter`/`ErrorCode` 봉투(#58) · `GlobalExceptionHandler` · `AuthProperties` 패턴(`@ConfigurationProperties`)

**Storage**: 없음 — 신규 마이그레이션 0 (R-06). `users` 읽기만(회원 claims)

**Build/Testing**: Maven(`backend/mvnw`), JUnit 5, Testcontainers(PostgreSQL), MockMvc — 003·004·005·013a와 동일 패턴. 신규 테스트 2개(통합 1 + 발급기 단위 1), 파일명은 infra-003 T015·T040이 지정한 경로를 그대로 써서 이중 구현을 차단한다

**Target Platform**: Docker Spring API (`backend/`)

**Project Type**: Web API — 기존 모놀리스에 `world` 패키지 신설

**Performance Goals**: 호출 빈도 = 사용자당 월드 입장 1회(+수동 재시도). DB 접근은 회원일 때 PK 단건 SELECT 1회. 캐시·인덱스 불필요

**Constraints**: 발급 무상태(FR-013) · 토큰 원문 로그 금지(FR-023, infra-003) · 설정 오류 시 기동 실패(fail-fast, T-24 원칙) · endpoint 하드코딩 금지(헌법 8조)

**Scale/Scope**: endpoint 1개 + 클래스 4개 + 설정 1블록 + 테스트 2개. Breaking 변경 0

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 후 재확인 — **PASS** (위반 0건)*

| 조 | 요구 | 이 계획에서 |
|---|---|---|
| 2 | 게임 서버는 실시간만 권위 | BE는 발급만, 접속·정원·재사용 차단은 게임 서버 몫. BE가 접속 상태를 조회하지 않는다 (R-10) |
| 3(정신)·14 | Spring 장애가 월드 입장을 막지 않는다 | 서명 자체 검증용 토큰 발급. 접속마다 조회하는 경로를 만들지 않는다 — 저장소 0 (R-06) |
| 8 | endpoint 하드코딩 금지 | 응답 값은 `app.world.*` 설정에서만. local/demo 프로필 분리 (R-07) |
| 9 | 층 파라미터 1차부터 | `worldId` optional 수용, `11F`만 유효, 위반 시 400 사유 명시 (R-03) |
| 12 | 게스트 비영속 | 게스트 발급 허용(둘러보기), 영속 상태 0 — 게스트 행·세션 기록을 만들지 않는다 (R-05) |
| 13 | Refresh 미전달, 4계층 분리 | 이 API의 입력은 AT(①), 출력은 Connection Token(③). Refresh는 등장하지 않는다 |
| 15 | Secret 커밋 금지 | `CONNECTION_TOKEN_SECRET`은 env 주입, `.env.example`에 이름만 |
| 16 | 클라이언트 주장 불신 | claims 전부 서버 유래 — 회원은 `users` 행, 게스트는 AT subject 파생 (데이터모델 불변식 2) |
| 24 | 계약 변경은 합의로만 | 전부 가산적(endpoint 신설). OpenAPI `worldId` 추가·게스트 nickname 규칙·Jira 2건 정정은 통보 목록으로 고정 (quickstart §4) |
| 27 | 기준선 동결 | `NetworkPlayer`·`ConnectionManager`·Unity 코드 일절 무접촉 — BE 파일만 |
| 29·30 | 기록·미정 항목 | HDD 작업일지 + 통보 대기 항목은 임의 확정 대신 quickstart §4에 등록 |

## Project Structure

### Documentation (this feature)

```text
specs/002-world-session/
├── spec.md            # 공동 정본 (Unity 리드 확정) — 무변경
├── plan.md            # Unity 파트 plan — 무변경
├── tasks.md           # Unity 파트 tasks — 무변경
└── BE/                # BE 실행 산출물 (#43 구조, 013a BE/와 대칭)
    ├── plan.md        # 이 파일
    ├── research.md    # Phase 0 — R-01~R-10
    ├── data-model.md  # Phase 1 — 스키마 0·값 객체·claims·불변식
    ├── quickstart.md  # Phase 1 — 검증 절차·통보 체크리스트
    └── tasks.md       # Phase 2 — /speckit-tasks 산출 (아직 없음)
```

**계약은 신규 작성하지 않는다** — 정본이 이미 있다: `specs/infra-003-unity-server-deploy/contracts/world-session.openapi.yaml` + `world-entry-token.md` (origin/develop, R-02). 002 밑에 사본을 만들면 #59가 경고한 복제 구조가 된다.

### Source Code (repository root)

```text
backend/src/main/java/com/example/ssafesta/world/          # [신설 패키지]
├── WorldSessionController.java   # POST /api/v1/world-sessions
├── WorldSessionService.java      # 신원 해석(회원 행 조회/게스트 파생) + 응답 조립
├── WorldEntryTokenIssuer.java    # HS256 전용 키 120s JWS 발급 (infra-003 T020과 동일 경로)
└── WorldProperties.java          # app.world.* record + 기동 검증 (scheme/port/secret)

backend/src/main/resources/
└── application-local.yml         # [수정] app.world.* local 값 + CONNECTION_TOKEN_SECRET 참조

backend/src/test/java/com/example/ssafesta/world/          # [신설]
├── WorldSessionApiIntegrationTest.java   # quickstart §3 — infra-003 T015와 동일 경로
└── WorldEntryTokenIssuerTest.java        # claims·TTL·jti·키 분리 — infra-003 T040과 동일 경로
```

**건드리지 않는 것**: `SecurityConfiguration`(`anyRequest().authenticated()`가 이미 회원·게스트를 통과시킨다 — R-08) · `JwtConfiguration`/`AccessTokenService`(AT 계층 무변경) · 마이그레이션(0) · `docs/08` §16(infra-003 T070 소유) · Unity·FE 코드(통보만).

## API 형태 (계약 확정분 + 이 plan의 확정)

```http
POST /api/v1/world-sessions
Authorization: Bearer <access token — 회원·게스트>
{ "worldId": "11F", "preferredPartyId": null }        # 본문 전체 optional

→ 200 {
    "sessionId": "ws_9f2…", "worldId": "11F", "channelId": "11F-01",
    "endpoint": { "scheme": "ws", "host": "127.0.0.1", "port": 7777 },
    "connectionToken": "<HS256 JWS, 120s>", "expiresAt": "2026-08-25T…Z"
  }
→ 400 VALIDATION_FAILED + FIELD_INVALID/field:"worldId"   # worldId ≠ "11F"
→ 401 UNAUTHORIZED (봉투)                                  # AT 없음/무효
→ 403                                                       # 정지 계정 (R-08)
```

`DELETE /world-sessions/{sessionId}`는 만들지 않는다 — 지울 서버 상태가 없다 (R-06).

## Complexity Tracking

없음. 신규 저장소 0·신규 오류 코드 0·보안 설정 변경 0. 새로 생기는 개념은 "AT와 분리된 서명 키" 하나이고, 그것이 이 기능의 요구사항 그 자체다(헌법 13·14조).

## Phase 2 준비 상태

- [x] Phase 0 research.md — NEEDS CLARIFICATION 0건 (통보 대기 2건은 §4 목록으로 관리, 블로커 아님)
- [x] Phase 1 data-model.md · quickstart.md — 계약은 infra-003 정본 재사용(신규 작성 없음)
- [x] Constitution 재확인 — PASS
- [ ] `/speckit-tasks`로 BE/tasks.md 생성 → 구현
