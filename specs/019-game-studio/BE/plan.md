# BE Implementation Plan: FESTA Game Studio

**Date**: 2026-08-21 (리드 초안) · **2026-08-24 구현 계획 반영 — #48, strdeok** | **Shared spec**: [../spec.md](../spec.md) | **API contract**: [../contracts/game-api.md](../contracts/game-api.md)

> **리드 초안의 문장은 지우지 않았다.** 이 개정은 그 위에 **어떻게 구현하는가**를 더한 것이고,
> 결정 근거는 [research.md](research.md) §7~§12다. 초안과 갈리는 지점은 두 곳뿐이며 둘 다
> 아래에 명시했다 — **패키지 배치**는 2026-08-25 #48에서 flat으로 확정됐고(§패키지 배치),
> **Published 캐시 정책**은 리드 본인의 #48 코멘트를 반영한 갱신이다(§Technical Context).

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
- Published body는 cache 가능, Portal resolution과 신규 진입 판정은 `no-store`
  - **갱신** — Published 포인터 URL은 `no-cache`+ETag이고, 긴 `max-age, immutable`은 version 고정 URL에만 적용한다. 초안의 *"Published body는 cache 가능"* 을 좁힌 유일한 지점이다. 근거는 **리드 본인의 #48
    코멘트**(2026-08-23) — *"Published 포인터 URL은 no-cache + ETag, immutable cache는 version 고정
    URL에만 적용"*. 초안 작성(08-21) 이후의 결정이라 초안이 틀린 것이 아니고, `game-api.md` §Runtime도
    이미 같은 문장이다. 다르게 읽으셨다면 알려 주십시오

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

리드 초안이 정한 경계다. 그대로 둔다.

```text
backend/src/main/java/com/example/ssafesta/game/
├── api/
├── application/
├── domain/
└── persistence/
backend/src/test/java/com/example/ssafesta/game/
backend/src/main/resources/db/migration/
```

### 패키지 배치 — flat 확정 (2026-08-25, GitLab #48)

**기존 Backend와 같은 flat 구조로 간다.** 리드 초안의 4계층(`api`/`application`/`domain`/`persistence`)은
채택하지 않는다 — 이 코드베이스의 전 패키지가 플랫이고(`booth`·`user`·`wallet`·`auth` 어디에도 하위
디렉터리가 없다), 019만 4계층을 가지면 같은 저장소에 두 배치가 공존한다. 019가 새 선례를 만들지 않는다.

```text
backend/src/main/java/com/example/ssafesta/game/
├── Game.java · GameDraft.java · GamePublishedVersion.java   (+Repository 3)
├── GameProjectValidator.java · GameProjectJson.java
├── GameDraftService.java · GamePublishService.java · GamePublishedQueryService.java
├── GameController.java
└── GameValidationFailedException.java · GameRevisionConflictException.java
backend/src/main/resources/db/migration/V13__game_studio.sql
backend/src/main/resources/game/game-project-v1.schema.json   # 계약 사본 (research §8)
backend/src/test/java/com/example/ssafesta/game/              # 통합 테스트 5~6
backend/src/test/resources/game/fixtures/                     # 계약 fixture 사본
```

파일 목록 자체(엔티티 3 + 검증기 2 + 서비스 3 + 컨트롤러 1 + 예외 2, V13, schema·fixture 사본)는
배치와 무관하게 같다.

> **예외 클래스는 2개다.** 값을 실어야 하는 것만 클래스를 만든다 — 검증 실패(`errors[]` rule 목록)와
> revision 충돌(현재 revision을 십진수로). 실을 값이 없는 나머지 `code` 7종(`GAME_NOT_FOUND`·
> `GAME_DELETED`·`GAME_NOT_PUBLISHED`·`GAME_NOT_PUBLIC`·`GAME_FORBIDDEN`·
> `GAME_SCHEMA_UNSUPPORTED`·`GAME_PROJECT_INVALID`)은 `throw new ApiException(ErrorCode.GAME_…)`로
> 끝난다 — 전부 봉투 `code`이고 rule이 아니다. booth가 이 자리에 얇은 클래스 11개(본문 3줄 + javadoc)를
> 둔 것은 각 예외가 `ErrorCode`를 **스스로** 들고 있게 해 컨트롤러 번역 누락을 막으려는 것이었는데
> (005에서 404가 500으로 새어나간 사고), `ApiException(ErrorCode)` 생성자를 직접 쓰면 같은 보장이
> 성립한다. 클래스 8개 ≈ -100줄.

## Implementation Phases

1. migration과 도메인/오류 경계를 추가한다.
2. revision-aware Draft와 Schema+semantic validation을 구현한다.
3. atomic Publish와 Published query를 구현한다.
4. `GAME_PORTAL` whitelist와 resolver를 구현한다.
5. soft delete, 회원 탈퇴 hard delete, 이력 보존 정책을 통합 테스트로 고정한다.

이 5단계가 019 전체다. **아래 8단계는 그중 #48 몫(1~3단계)을 task 단위로 쪼갠 것**이고 4·5단계를
대체하지 않는다 — 4단계는 #56(PR-2), 5단계는 T085이며 그 endpoint가 계약에 없어 결정 ②로 올려 두었다.

## Implementation Order — #48 몫(리드 1~3단계)의 8단계 분해 (T014~T088, 15 tasks)

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

- ~~**계약 결정 ①** — `GET /draft` Draft 없음~~ → **2026-08-25 확정: 204 No Content** (GitLab #104).
  `GAME_DRAFT_NOT_FOUND`는 폐기했다. 첫 저장은 `expectedRevision: 0`으로 오므로 **draft row가 없고
  `expectedRevision: 0`이면 최초 생성으로 처리하고 revision 1을 반환**한다 — 그 밖의 값은 409.
  이 규칙이 없으면 모든 게임의 첫 저장이 409로 튕긴다.
- **확인 A** (계약 표에 없는 이 plan 고유 항목 — 번호를 계약 결정과 섞지 않는다) —
  **`project.gameId`·`project.revision`은 서버 소유 메타**다. 요청의 `gameId` 불일치는
   `MALFORMED_PROJECT`로 거부하고, 저장 시 서버가 새 revision을 project에 기록해 응답 일치
   계약(§Draft 저장)을 성립시킨다. "자동 보정 금지"의 예외가 아니라 발급이다 (research §10).

## Fixed Policies

- 일반 게임 삭제는 `deleted_at` soft delete다.
- 회원 탈퇴는 Game, Draft, Published Version, Asset, Score까지 hard delete한다.
- Published Version 이력은 Game이 존속하는 동안 유지하고 hard delete 때 제거한다.
- 랭킹은 P1 표시 전용 후보이며 Coin/Reward/Inventory와 FK도 연결하지 않는다.
- 공개 중단은 신규 REST 조회만 차단한다. 진행 중 로컬 세션을 끊는 소켓은 만들지 않는다.
- 공개 `config_id`는 `INTEGER UNIQUE NOT NULL CHECK (config_id > 0)`, 전용 sequence는 1부터 시작한다.
  - 이 항목은 `game_portal_bindings`와 함께 #56(PR-2, V14) 몫이다 — 이 PR 범위 밖이다.

## Rollout — PR 단위

| PR | 범위 | 게이트 |
|---|---|---|
| 계약 MR (준비됨) | `game-api.md` 오류 표·shape + 이 plan | 정정 커밋 → 리드 브랜치 위 재배치 → push |
| **구현 PR-1 = #48** | 위 8단계 (V13) | 새 이름 18개 + 계약 결정 ①·② 승인 (②가 부결되면 T085 제외) |
| 구현 PR-2 = #56 | V14 + `GAME_PORTAL` whitelist + resolver + no-store endpoint (T051·T053·T054·T084) | PR-1 머지 + game 파트 AABB |
| 구현 PR-3 = #69 | Asset 업로드 (계약 이어쓰기 → R2 연동) | PR-1 계약 확정 + FE assetId 답 |
| 잔여 | T085(공개 중단·삭제 endpoint — 결정 ②) · T086(P1) · #78 E2E · #81(spec 개정 선행) | 각 결정 |

## 구현 PR-3 = #69 Asset 업로드 — 실행 계획 (Jira S15P21A604-107)

계약 [`../contracts/game-asset-upload.md`](../contracts/game-asset-upload.md) v1.0 이 정본이다.
FE 는 그 계약대로 `festa-frontend/src/game-studio/studio/assets/remoteAssetRepository.ts` 를 이미
구현해 develop 에 넣었다. **BE 는 계약과 그 클라이언트에 맞추기만 한다 — 형태를 다시 정하지 않는다.**

브랜치: `feat/S15P21A604-107-game-asset-upload` (develop 6f58846c 기점).

### FE 가 실제로 호출하는 표면

`remoteAssetRepository.ts` 가 부르는 것은 6개 endpoint 중 3개다. 슬라이스 1 은 이 3개로 끊는다.

| 호출 | 요청 | FE 가 파싱하는 응답 필드 |
|---|---|---|
| `POST /api/v1/games/{gameId}/assets` | `{kind, contentType, byteSize, fileName}` | `assetId` · `uploadUrl` · `requiredHeaders` |
| presigned `PUT {uploadUrl}` | 파일 본문 + `requiredHeaders` | — (우리 서버 아님) |
| `POST /api/v1/games/{gameId}/assets/{assetId}/complete` | 본문 없음 | `status` · `source` · `kind` |
| `GET /api/v1/games/{gameId}/assets/{assetId}/content` | — | 바이트 |

FE 가 강제하는 두 가지를 어기면 그쪽 코드가 즉시 거부한다.

- `assetId` 는 `^[A-Za-z][A-Za-z0-9_-]{0,63}$` 를 만족해야 한다 (`STABLE_ID`). 계약 §2 의 26자를 쓴다.
- `complete` 의 `source` 는 서버가 문자열을 만들어 내려준다. FE 는 조립하지 않고 `gameId`·`assetId`
  일치까지 대조한다 — 형식을 바꾸면 저장은 되고 표시만 깨지는 실패가 된다.

### 단계

| 순서 | Task | 산출 |
|---|---|---|
| 1 | T094 | `V18__game_assets.sql` — 계약 §8 DDL, `UNIQUE(game_id, asset_id)` |
| 2 | T095 | `ErrorCode` 에 `GAME_ASSET_*` 11종 (계약 §6) |
| 3 | T096 | 저장소 포트 + S3-compatible 어댑터, `compose.yaml` 에 minio |
| 4 | **T097 + T100 한 커밋** | 발급 endpoint 와 `isPersistableSource()` 완화 |
| 5 | T098 | `complete` — 검증·상태 전이·멱등 |
| 6 | T099 | `/content` 302 |
| 7 | T101 · T102 | E2E 1개 + 거부 7종 |

### 결정 4개

**① 저장소는 어댑터 뒤에 둔다. R2 없이 진행한다.**
R2 자격증명은 infra Jira S15P21A604-232 대기지만 R2·MinIO 가 같은 S3 프로토콜이라 바뀌는 것은
endpoint·credential·`provider` 값뿐이다. 로컬은 `compose.yaml` 의 minio, 테스트는 MinIO
Testcontainer 로 돌린다. 계약 형태는 저장소 종류와 무관하다.

**② T097 과 T100 은 반드시 같은 커밋이다.**
계약 §9 가 명문화했고 `GameProjectValidator` 주석에도 이미 적혀 있다. 갈리면 존재할 수 없는
reference 를 저장할 수 있게 된다 — 발급 없이 완화하면 유령 참조가, 완화 없이 발급하면 업로드한
Asset 을 Draft 에 못 넣는다.

**③ 검증은 전부 `complete` 시점이다.**
presigned PUT 이라 서버는 업로드 본문을 보지 못한다. 시작 요청의 `contentType`·`byteSize` 는
거절용으로만 쓰고, 통과 판정은 `complete` 의 HEAD + magic number + 실제 디코드가 한다.
디코드는 JDK `ImageIO` 라 저장소와 무관하다.

**④ 검증기는 순수하게 둔다.**
§9 는 `(game_id, asset_id)` 존재와 `status=READY` 조회를 요구하지만 `GameProjectValidator` 에
리포지토리를 넣지 않는다. 호출부(`GameDraftService`·`GamePublishService`)가 그 Game 의 READY
`assetId` 집합을 읽어 `validateForDraft`/`validateForPublish` 에 넘긴다. 검증기는 지금처럼
입력만 보고 판정한다.

### 슬라이스 2 — 뒤로 미루는 것과 이유

T103~T107 (목록·단건·삭제·탈퇴 hard delete·sweeper). FE 편집기 UI(#69 ①~④)가 미착수라
소비자가 없고, T107 은 `@Scheduled` 가 코드에 0개라 007 미완료 문서 sweeper 가 선행이다
(계약 §7.1 — "019 는 그 주기에 얹고 스케줄러를 새로 만들지 않는다").

### 검증

- E2E 1개: 시작 → presigned PUT → `complete` → `READY` → `/content` 302 (MinIO Testcontainer)
- 거부 7종: 위장 MIME · 디코드 실패 · SVG · 5 MiB 초과 · 4096px 초과 · 타 Game asset 참조 ·
  `UPLOADING` 참조 Draft 저장
- 회귀: 기존 통합 테스트 전량

## Verification

권한, revision conflict, invalid reference, immutable Published, transaction rollback, soft/hard delete,
Portal owner/status, `config_id` 0·음수·overflow 거부와 2147483647 왕복을 integration test로 검증한다.

**이 PR(#48)에서 실제로 태우는 범위와 방법**입니다 — 위 목록 중 soft/hard delete는 T085(결정 ②),
Portal·`config_id`는 #56이라 각각 PR-2·잔여로 갑니다.

- 회귀 232 + 신규 통합 테스트. quickstart 검증 행렬 13개 중 **#48 몫 = 1~6·13**
  (7은 T085, 8~12는 #56).
- fixture 재현: `node specs/019-game-studio/contracts/fixtures/validate-fixtures.mjs` 와
  서버 검증기가 같은 manifest에 같은 판정 — 단, `asset://local` 은 fixture가 통과시키고
  서버가 거부한다(계약 명시 — fixture는 하한).
- FE 통합: 머지 후 `VITE_GAME_STUDIO_API_ENABLED=true` E2E는 FE 몫(PR #72 adapter)으로 통보.
