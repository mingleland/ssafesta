# BE Implementation Plan: FESTA Game Studio

**Date**: 2026-08-21 (리드 초안) · **2026-08-24 구현 계획 반영 — #48, strdeok** | **Shared spec**: [../spec.md](../spec.md) | **API contract**: [../contracts/game-api.md](../contracts/game-api.md)

> 리드 초안은 계약과 완료 조건만 소유했다(tasks.md 명시). 이 개정은 그 내용을 전부 보존하고
> **어떻게 구현하는가**를 채운 것이다. 결정 근거는 [research.md](research.md) §7~§12.

## Summary

기존 Spring 앱에 `game` 도메인을 추가해 mutable Draft, immutable Published Version, 현재 공개본 포인터,
Game Portal Binding을 소유한다. 게임 프레임 실행·2D 물리·진행 중 로컬 세션은 서버 책임이 아니다.

구현의 중심은 세 가지다.

1. **검증기가 이슈의 절반이다.** Draft·Publish가 같은 구조·schema·상한·참조 무결성을 보고(#48 확정),
   `contracts/fixtures/` manifest의 positive/negative를 그대로 재현해야 한다(quickstart). rule 어휘는
   `game-api.md` 오류 표가 정본이며 서버가 새 이름을 만들지 않는다.
2. **선례가 이미 저장소에 있다.** 005 booth가 같은 모양이다 — Draft/PublishedVersion 2테이블,
   `insertIfAbsent`/`updateIfRevisionMatches` 낙관적 잠금, validate→append→pointer 단일 트랜잭션,
   JSONB + `@JdbcTypeCode(SqlTypes.JSON)`. 이식하고 019가 다른 지점만 갈아 끼운다
   (십진수 `CURRENT_REVISION`, revision INTEGER, 상한 7종, JSON Schema 검증).
3. **실패를 조용히 삼키지 않는다** (T-24, FR-034). clamp·unknown field drop·참조 치환 금지는
   완료 조건이자 테스트 대상(T088)이다.

## Technical Context

- Java 21, Spring Boot 4.1, Spring MVC/JPA/Security, Flyway, PostgreSQL 17 JSONB
- **Maven** (`backend/mvnw`) — 회귀 기준선 **232 tests** (2026-08-24, 013a 이후)
- 신규 의존성 **1**: `com.networknt:json-schema-validator` (draft 2020-12 — research §8)
- `games`, `game_drafts`, `game_published_versions` (**V13** — V12는 #62 슬롯 12 시드가 이미 사용) / `game_portal_bindings`는 #56에서 V14
- Draft save는 `expectedRevision` 낙관적 잠금, revision은 서버 발급 카운터 (research §10)
- Publish는 validate → immutable append → pointer update 단일 transaction
- Published body는 `no-cache`+ETag, Portal resolution은 `no-store` (#56)

## Constitution Check — PASS

| 조 | 요구 | 이 계획에서 |
|---|---|---|
| 1 | 영구 상태 SoT는 Spring | Draft·Published·pointer 전부 Spring. FE는 mock/adapter 뒤에서 대기(PR #72) |
| 2 | 게임 서버는 실시간만 | 해당 없음 — Unity·게임 서버는 GameProject를 만지지 않는다(FR-017) |
| 12·16 | 게스트 비영속·클라이언트 불신 | Authoring은 `MemberPrincipal.requireMemberId`(403 `MEMBER_ONLY`), 소유자는 JWT subject로만. `project.gameId`·`revision`은 서버가 대조·발급 |
| 20 | 경제는 REST/Ledger로만 | MVP에 Coin 없음(FR-022). #81은 candidate로 격리 — 이 구현에 넣지 않는다 |
| 22·23 | (부스·아바타 조항) | 해당 없음 — Layout 연결은 #56 |
| 24 | 계약 변경은 합의로만 | 전부 가산적(신규 endpoint·테이블). 새 이름 18개(rule 16 + code 1 + `unavailableReason` 1)와 계약 결정 ①~⑥은 게시 후 승인 대기 |
| 28 | 범위 통제 | 이 PR은 #48의 15개 task만. T085(문 없음)·T086(P1)·#56·#69 제외 |
| 29 | 기록 의무 | `docs/HDD/작업일지.md` + 트러블 시 T-번호 |
| 30 | 미정 임의 확정 금지 | 새 이름 18개·계약 결정 ①~⑥·확인 A(revision 소유 명문화)를 #48 승인으로 푼다. AABB(#56)·assetId(#69)는 대기 |

## Source Boundary

```text
backend/src/main/java/com/example/ssafesta/game/       # 플랫 패키지 — research §11
├── Game.java · GameDraft.java · GamePublishedVersion.java   (+Repository 3)
├── GameProjectValidator.java · GameProjectJson.java
├── GameDraftService.java · GamePublishService.java · GamePublishedQueryService.java
├── GameController.java
└── GameValidationFailedException.java · GameRevisionConflictException.java   # 2개만
backend/src/main/resources/db/migration/V13__game_studio.sql
backend/src/main/resources/game/game-project-v1.schema.json   # 계약 사본 (research §8)
backend/src/test/java/com/example/ssafesta/game/              # 통합 테스트 5~6
backend/src/test/resources/game/fixtures/                     # 계약 fixture 사본
```

> 리드 초안의 `api/ application/ domain/ persistence/` 4계층 스케치는 채택하지 않는다 —
> 이 코드베이스의 전 패키지(booth·user·wallet·auth)가 플랫이고, 019만 계층을 갖는 것이
> 더 큰 비일관이다 (research §11).
>
> **예외 클래스는 2개다.** 값을 실어야 하는 것만 클래스를 만든다 — 검증 실패(`errors[]` rule 목록)와
> revision 충돌(현재 revision을 십진수로). 실을 값이 없는 나머지 `code` 8종(`GAME_DRAFT_NOT_FOUND`·
> `GAME_NOT_FOUND`·`GAME_DELETED`·`GAME_NOT_PUBLISHED`·`GAME_NOT_PUBLIC`·`GAME_FORBIDDEN`·
> `GAME_SCHEMA_UNSUPPORTED`·`GAME_PROJECT_INVALID`)은 `throw new ApiException(ErrorCode.GAME_…)`로
> 끝난다 — 전부 봉투 `code`이고 rule이 아니다. booth가 이 자리에 얇은 클래스 11개(본문 3줄 + javadoc)를
> 둔 것은 각 예외가 `ErrorCode`를 **스스로** 들고 있게 해 컨트롤러 번역 누락을 막으려는 것이었는데
> (005에서 404가 500으로 새어나간 사고), `ApiException(ErrorCode)` 생성자를 직접 쓰면 같은 보장이
> 성립한다. 클래스 8개 ≈ -100줄.

## Implementation Order — 8단계 (T014~T088, 15 tasks)

| 단계 | Tasks | 산출물 |
|---|---|---|
| 1 기반 | T014·T015·T016 | 패키지 · V13(3테이블 + `(id, published_version)` 복합 FK, `SET NULL` 컬럼 한정) · `ErrorCode` GAME_* + 예외(십진수 `CURRENT_REVISION`) |
| 2 테스트 선행 | T032·T033 | revision 충돌·권한·불변 Publish·부분 반영 없음 + **fixture manifest 재현**(파라미터라이즈드) |
| 3 검증기 | T037·T079 | `validateForDraft`/`validateForPublish` (005와 같은 두 진입점) — 순서·`maxItems`→rule 매핑은 research §7. Asset source 스킴은 Draft·Publish 공통 |
| 4 엔티티·Draft | T035·T036 | JSONB 엔티티 3종 + `insertIfAbsent`/`updateIfRevisionMatches` + `POST /games` |
| 5 Publish | T038 | read→재검증(+소유권·Dialogue·완료 경로)→`saveAndFlush` append→pointer, 단일 트랜잭션 |
| 6 Endpoints | T039 | `GET`·`PUT /draft` · `POST /publish` · `GET /versions` 배선 (`POST /games`는 4단계, `GET /published`는 7단계) + SecurityConfiguration(Authoring member-only) |
| 7 Published 조회 | T044·T046 | `no-cache`+ETag(`W/"g{id}-v{n}"`), 공개/비공개/미발행/삭제 구분, 게스트 허용 |
| 8 정책 검증 | T087·T088 | 게스트 매트릭스 · 자동 보정 0건 증명 |

## 구현 중 계약 확인 필요 — #48 승인 묶음에 포함

- **계약 결정 ①** (`game-api.md` §결정 필요의 번호) — `GET /draft` Draft 없음: 404
  `GAME_DRAFT_NOT_FOUND` vs 204 (FE `load`는 204만 `null`로 읽음 — 404면 새 게임 첫 방문마다 편집기가
  오류 상태). BE는 204를 권고.
- **확인 A** (계약 표에 없는 이 plan 고유 항목 — 번호를 계약 결정과 섞지 않는다) —
  **`project.gameId`·`project.revision`은 서버 소유 메타**다. 요청의 `gameId` 불일치는
   `MALFORMED_PROJECT`로 거부하고, 저장 시 서버가 새 revision을 project에 기록해 응답 일치
   계약(§Draft 저장)을 성립시킨다. "자동 보정 금지"의 예외가 아니라 발급이다 (research §10).

## Fixed Policies (리드 초안 유지)

- 일반 게임 삭제는 `deleted_at` soft delete다.
- 회원 탈퇴는 Game, Draft, Published Version, Asset, Score까지 hard delete한다.
- Published Version 이력은 Game이 존속하는 동안 유지하고 hard delete 때 제거한다.
- 랭킹은 P1 표시 전용 후보이며 Coin/Reward/Inventory와 FK도 연결하지 않는다.
- 공개 중단은 신규 REST 조회만 차단한다. 진행 중 로컬 세션을 끊는 소켓은 만들지 않는다.
- 공개 `config_id`는 `INTEGER UNIQUE NOT NULL CHECK (config_id > 0)`, 전용 sequence는 1부터 시작한다 (#56·V14).

## Rollout — PR 단위

| PR | 범위 | 게이트 |
|---|---|---|
| 계약 MR (준비됨) | `game-api.md` 오류 표·shape + 이 plan | 정정 커밋 → 리드 브랜치 위 재배치 → push |
| **구현 PR-1 = #48** | 위 8단계 (V13) | 새 이름 18개 + 계약 결정 ①·② 승인 (②가 부결되면 T085 제외) |
| 구현 PR-2 = #56 | V14 + `GAME_PORTAL` whitelist + resolver + no-store endpoint (T051·T053·T054·T084) | PR-1 머지 + game 파트 AABB |
| 구현 PR-3 = #69 | Asset 업로드 (계약 이어쓰기 → R2 연동) | PR-1 계약 확정 + FE assetId 답 |
| 잔여 | T085(공개 중단·삭제 endpoint — 결정 ②) · T086(P1) · #78 E2E · #81(spec 개정 선행) | 각 결정 |

## Verification

- 회귀 232 + 신규 통합 테스트. quickstart 검증 행렬 13개 중 **#48 몫 = 1~6·13**
  (7은 T085, 8~12는 #56).
- fixture 재현: `node specs/019-game-studio/contracts/fixtures/validate-fixtures.mjs` 와
  서버 검증기가 같은 manifest에 같은 판정 — 단, `asset://local` 은 fixture가 통과시키고
  서버가 거부한다(계약 명시 — fixture는 하한).
- FE 통합: 머지 후 `VITE_GAME_STUDIO_API_ENABLED=true` E2E는 FE 몫(PR #72 adapter)으로 통보.
