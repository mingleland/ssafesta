# Implementation Plan: 아바타 저장 API (013a BE분)

**Branch**: `feature/avatar-save-api` (예정) | **Date**: 2026-08-24 | **Spec**: [spec.md](../spec.md)

**Input**: `specs/013-avatar-customization/spec.md` + `contracts/avatar-profile-api.md` (#24 확정) + `docs/sdd/parts/BE.md` §013a

## Summary

Unity가 Mock 뒤에서 기다리고 있는 아바타 영구 저장(FR-013)의 서버 몫을 구현한다. **`PUT /api/v1/users/me/avatar`** 로 저장하고 **`GET /api/v1/users/me` 응답에 `avatarCode`를 포함**해 재접속 복원 경로를 연다.

설계의 중심은 세 가지다.

1. **서버에게 `avatarCode`는 불투명 문자열이다.** 파싱·정규화·기본값 대체를 하지 않는다(계약 명령). 검증은 위임받은 두 가지 — 길이(≤3800, Unity `MaxEncodedLength`와 동일)와 문자셋(인쇄 가능 ASCII) — 뿐이고, **저장한 바이트열과 돌려주는 바이트열이 동일**함을 테스트로 고정한다(005 `layout_json` 왕복 무손실 원칙과 동일).
2. **스키마 작업이 0이다.** `users.avatar_code TEXT`(V10)와 JPA 매핑이 이미 있다. 이 기능은 그 컬럼에 처음으로 쓰기 경로를 붙이는 것이다 — T-24의 교훈으로 컬럼이 먼저 준비된 드문 경우다.
3. **실패를 조용히 삼키지 않는다**(FR-012, T-24). 길이 초과와 문자셋 위반은 **다른 문장**의 400으로 구분되고, `FIELD_INVALID`/`field: "avatarCode"` 봉투(#58·T058)로 나간다. 새 오류 코드·rule 신설이 0개다.

결정 근거 전체: [research.md](research.md) R-01~R-08.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA, Spring Security (Resource Server), Jackson. 재사용: `MemberPrincipal`(게스트 차단) · `GlobalExceptionHandler`+`ApiErrorDetail.field()`(T058 봉투) · `MyAccountController`(기존 `/users/me` 계열)

**Storage**: PostgreSQL 17 — `users.avatar_code TEXT` (V10 완료). **신규 마이그레이션 없음**

**Build/Testing**: **Maven**(`backend/mvnw`, Spring Boot 4.1.0 parent) — Gradle이 아니다. JUnit 5, Testcontainers(PostgreSQL), MockMvc — 003·004·005와 동일 패턴. 신규 통합 테스트 1개(`MyAccountAvatarApiIntegrationTest`). 회귀 기준선 **217 passed**(2026-08-24 실측)

**Target Platform**: Docker Spring API (`backend/`)

**Project Type**: Web API — 기존 모놀리스에 endpoint 1개 추가

**Performance Goals**: 단건 PK 행 UPDATE/SELECT. 월드 입장 전 1회 + 외형 변경 시 1회 호출 — 인덱스·캐시 불필요

**Constraints**: 왕복에서 값이 바뀐 사례 0건(SC-002·무손실 불변식) · 저장 실패가 월드 이용을 막지 않음(FR-014 — 클라이언트 몫이지만 서버는 명확한 오류 봉투로 협조) · 무반응 실패 0건(SC-005 — 모든 거부에 사유 문장)

**Scale/Scope**: 사용자당 1값. endpoint 1개 + 응답 필드 1개 + 정책 클래스 1개 + 테스트 1개

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 후 재확인 — **PASS** (아래 표, 위반 0건)*

| 조 | 요구 | 이 계획에서 |
|---|---|---|
| 1 | Avatar 영구 상태의 SoT는 Spring | 저장·복원 모두 `users.avatar_code` 단일 지점. Unity·React는 세션 캐시만 |
| 12 | 게스트 비영속 | `MemberPrincipal.requireMemberId` → 게스트 403 `MEMBER_ONLY`. 클라이언트의 "게스트면 호출 안 함"만 믿지 않는다(16조) |
| 16 | 클라이언트 주장 불신 | userId는 JWT subject로만 유도(요청 body에 없음). 값 자체는 길이·문자셋 게이트 — 폭주 payload 차단 |
| 23 | 저장 컬럼 `TEXT`, "29~32자" 무효 | V10 완료 상태 유지. 검증 상한 3800(Unity 소유 값) — **하향 금지**, DB CHECK도 두지 않는다(상한 소유자가 서버가 아니다) |
| 24 | 계약 변경은 합의로만 | 전부 **가산적**: endpoint 신설 + 응답 필드 추가. Breaking 없음. 다만 통보 3건을 quickstart §4에 고정 — Unity(PATCH 주석 정정·문자셋 확인), FE(`avatarCode` 필드), `docs/08` §2 갱신 |
| 25 | 텍스트 UI는 React | 해당 없음 — 이 기능에 UI가 없다. 커스터마이징 창은 Unity `CharacterLobby`(2026-08-16 결정) |
| 29 | 기록 의무 | `docs/HDD/작업일지.md` + 트러블 시 T-번호 |
| 30 | 미정 항목 임의 확정 금지 | C-04(문자열 형식)는 Unity 몫 미확정이나 **BE 무관**(불투명 문자열 — R-05). 문자셋 값은 위임받은 검증의 구현이되 생산자 실측에서 유도했고 통보로 확인받는다(R-03). 임의 확정한 계약값 없음 |

## Project Structure

### Documentation (this feature)

```text
specs/013-avatar-customization/
├── spec.md                      # 공동 정본 (Unity 리드 확정)
├── contracts/
│   ├── avatar-profile-api.md    # 이 plan의 계약 — 구현 후 상태줄 갱신(quickstart §4)
│   ├── avatar-bridge.md         # Unity↔React (BE 무관)
│   └── network-avatar-config.md # Unity 네트워크 (BE 무관)
├── Unity/                       # Unity 파트 산출물 (기존)
└── BE/                          # BE 실행 산출물 (#43 구조, Unity/와 대칭)
    ├── plan.md                  # 이 파일
    ├── research.md              # Phase 0 — R-01~R-08
    ├── data-model.md            # Phase 1 — 스키마 0·불변식·검증 규칙
    ├── quickstart.md            # Phase 1 — 검증 절차·통보 체크리스트
    └── tasks.md                 # Phase 2 — /speckit-tasks 산출 (아직 없음)
```

### Source Code (repository root)

```text
backend/src/main/java/com/example/ssafesta/user/
├── MyAccountController.java    # [수정] @PutMapping("/avatar") 추가, MyAccountResponse에 avatarCode 필드
├── AvatarCodePolicy.java       # [신설] 길이·문자셋 검증 (NicknamePolicy 대칭 — R-06)
└── User.java                   # [수정] changeAvatarCode() 도메인 메서드 (컬럼·매핑은 기존)

backend/src/test/java/com/example/ssafesta/user/
└── MyAccountAvatarApiIntegrationTest.java  # [신설] quickstart §3 시나리오 전부
```

**건드리지 않는 것**: 마이그레이션(신규 없음) · `GlobalExceptionHandler`(T058 그대로) · `SecurityConfiguration`(`/users/me/**`는 기존 인증 경로) · Unity·FE 코드(통보만).

## API 형태 (계약 확정분 + 이 plan의 확정)

```http
PUT /api/v1/users/me/avatar
Authorization: Bearer <member access token>
{ "avatarCode": "fa|3=SK_Hair_Long_01|c=FF8800" }

→ 200 { "avatarCode": "fa|3=SK_Hair_Long_01|c=FF8800" }   # echo — 저장한 그대로
→ 400 VALIDATION_FAILED + FIELD_INVALID/field:"avatarCode"  # blank·3801자↑·비인쇄문자, 사유별 다른 문장
→ 401 (미인증) / 403 MEMBER_ONLY (게스트)
```

```http
GET /api/v1/users/me
→ 200 { "userId": 1, "nickname": "덕", "status": "ACTIVE", "providers": [...], "avatarCode": "fa|…" | null }
```

`GET /users/me/avatar`(별도 조회)와 삭제 경로는 **만들지 않는다** — 소비자가 없다(R-02, data-model §4).

## Complexity Tracking

없음. 신규 층·신규 오류 어휘·신규 스키마 0. 기존 패턴(`NicknamePolicy`·T058 봉투·`MemberPrincipal`)의 반복이라 복잡도 추가 요인이 없다.

## Phase 2 준비 상태

- [x] Phase 0 research.md — NEEDS CLARIFICATION 0건
- [x] Phase 1 data-model.md · quickstart.md — 계약은 기존 `contracts/avatar-profile-api.md` 재사용(신규 작성 없음, 구현 후 상태줄만 갱신)
- [x] Constitution 재확인 — PASS
- [ ] `/speckit-tasks`로 tasks.md 생성 → 구현
