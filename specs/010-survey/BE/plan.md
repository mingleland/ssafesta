# Implementation Plan: 설문 (010 BE분)

**Branch**: `feat/S15P21A604-130-survey-domain` (origin/develop 기준) | **Date**: 2026-09-07 | **Spec**: [../spec.md](../spec.md)

**Input**: `specs/010-survey/spec.md`(공동 정본) + Clarifications C-01~C-09(C-06~C-09는 BE 결정 · GitLab #133 통보, C-01·02·03·05는 기획 대기) + `docs/08` §9 stub + `docs/09` §15~§18 + FE Port(`festa-frontend/src/entities/survey/api.port.ts`, S15P21A604-368·-369) + `docs/sdd/parts/BE.md` §010

> **`BE/spec.md`는 없고 만들지도 않는다.** spec-kit이 `FEATURE_SPEC=BE/spec.md`를 조립하지만
> 명세 정본은 **상위 `../spec.md`** 하나다 (#43 — 정본 2벌은 drift). `speckit-analyze`는 기본
> 경로 대신 상위 `spec.md` + `BE/plan.md` + `BE/tasks.md`를 명시해 대조한다.

## Summary

설문 6테이블은 V1부터 있는데 **Java 코드가 0줄**이다. 운영자가 설문을 만들 방법도, 방문자가 답할 방법도, 결과를 볼 방법도 없다. FE는 이미 mock으로 응답 화면·Builder·결과 화면을 완성했고(S15P21A604-368·-369·-133·-194) 오버레이에 "응답 저장은 준비 중입니다" 고지를 띄우고 있다. endpoint 6개를 열어 그 고지를 지운다.

> **2026-09-11 추가 — 부스 밖 이벤트 설문** (S15P21A604-621, GitLab #173). 같은 6테이블 위에
> `surveys.survey_key`(V29)를 얹어 부스에 속하지 않는 설문을 하나 두고, 조회 endpoint 하나
> (`GET /api/v1/event-surveys/{surveyKey}/run`)를 더한다. **제출만 기존 경로를 그대로 쓴다** —
> 별도 표를 만들면 문항·선택지·응답·답변과 검증·제출이 통째로 복제되고 문항 유형이 늘 때마다
> 두 곳을 고쳐야 한다. 그래서 endpoint 는 7개, 컨트롤러·서비스 구성은 그대로다.
>
> **결과·운영 조회는 범위 밖이다** (2026-09-13 정정). 처음 이 블록에 "제출·결과" 라고 적었는데
> 구현과 다르다 — 이벤트 설문 id 로 결과를 조회하면 `SURVEY_NOT_FOUND` 다. 그 경로는 부스
> 소유자용이라 `boothId` 없는 설문을 통과시키면 가드가 null 을 들고 내려가 500 이 된다. 계약서도
> 운영 결과·추첨을 범위 밖으로 둔다. 집계·추첨이 필요해지면 별도 경로로 연다.

설계의 중심은 다섯이다.

1. **부스당 설문 1개는 DB가 지킨다** (C-06). 애플리케이션 검사만 두면 동시 `PUT` 두 건이 각각 "없음"을 보고 둘 다 만든다. `V22`로 `ux_surveys_booth` 유니크 인덱스를 걸고 제약 위반을 번역한다 — `V7__booth_one_per_owner.sql`·`V14__project_one_per_booth.sql`이 같은 이유로 만들어진 선례다 (R-01).
2. **게스트 응답을 스키마가 받아들이게 바꾼다** (C-05). V1의 `respondent_user_id NOT NULL` + `UNIQUE(survey_id, respondent_user_id)`는 게스트 응답을 **구조적으로 불가능**하게 만든다. nullable로 내리고 `respondent_guest_key`를 더해 xor CHECK로 묶고, 1인 1응답을 **부분 유니크 인덱스 두 개**로 각각 건다. 게스트는 지갑이 없으므로(헌법 12조) 보상 있는 설문은 `403 MEMBER_ONLY`로 막는다 (R-02).
3. **문항 교체는 벌크 삭제 후 삽입이다.** 중첩 `@OneToMany(cascade=ALL, orphanRemoval=true)`로 만들면 Hibernate가 자식 삭제를 삽입보다 뒤로 미뤄 `UNIQUE(survey_id, display_order)`와 `survey_options.question_id` FK에 걸릴 수 있다. 저장소 main에 `@OneToMany`가 **0건**이고 모든 엔티티가 평면 `Long` 참조를 쓰는 것도 같은 이유다 — `GameAssetRepository.deleteAllByGameId`의 `@Modifying(clearAutomatically, flushAutomatically)` 모양을 따른다 (R-03).
4. **보상은 응답 flush 뒤, 같은 트랜잭션에서** `WalletService.credit`을 부른다 (FR-005·헌법 20조). 멱등키는 `SURVEY_REWARD:{surveyId}:{userId}`이고 `survey_responses.reward_ledger_entry_id UNIQUE`가 두 번째 방어다. 순서가 뒤바뀌면 안 된다 — PostgreSQL은 제약 위반 후 트랜잭션을 abort 상태로 두므로 중복 제출 catch 뒤에는 아무 쿼리도 실행할 수 없다 (R-04).
5. **`PUT`의 누락 필드는 "유지"다** (C-07). FE `saveDraft`는 `{title, questions}`만 보내므로 `rewardCoin`을 일반 필드로 받으면 **저장마다 보상이 0으로 지워진다.** `common/PresenceField`로 키 존재를 판정한다 — 016이 밟은 자리다(T-97) (R-05).

**신규 테이블 0 · 신규 컬럼 3 · 신규 최상위 `code` 4개**(`SURVEY_NOT_FOUND`·`SURVEY_CLOSED`·`SURVEY_ALREADY_RESPONDED`·`SURVEY_LOCKED`) · 신규 `rule` 0. 새 예외 클래스도 만들지 않는다 — `ApiException(ErrorCode.SURVEY_*)`를 인라인으로 던진다 (R-06).

공통 부품 셋을 손댄다: `BoothAccessGuard.requireVisitorVisible` 승격(복제본 3개 위에 4번째를 만들지 않는다, R-07), `BoothRepository.findWithSharedLockById` 추가(R-08), 그리고 **`AccountDeletionService`의 설문 6줄 수정** — 설문만 `created_by_user_id`로 키를 잡고 있어 첫 쓰기 경로가 열리는 순간 소유자 탈퇴가 FK 위반으로 실패한다 (R-09).

결정 근거 전체: [research.md](research.md) R-01~R-12.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA, Spring Security(Resource Server), Jackson 3, Flyway. **신규 의존성 0**.

**Storage**: PostgreSQL 17 — `surveys`·`survey_questions`·`survey_options`·`survey_responses`·`survey_answers`·`survey_answer_options` 6테이블이 V1부터 존재한다. **신규 테이블 0**, 마이그레이션 `V22`는 유니크 인덱스 2개·CHECK 3개·컬럼 3개·부분 유니크 2개다. `docs/09` §16~§18의 컬럼명(`type`·`title`·`order_no`·`text_value`·`selected_option_ids JSONB`)은 **V1 DDL과 다르고 V1이 정본**이다 (R-10).

**Build/Testing**: **Maven** (`backend/mvnw`, `backend\mvnw.cmd`). JUnit 5, Testcontainers(`pgvector/pgvector:pg17` + `redis:7.2-alpine`), MockMvc. **회귀 기준선 843건**(2026-09-07 origin/develop, 실패 0).

**Target Platform**: Docker Spring API (`backend/`)

**Project Type**: Web API — 기존 모놀리스에 flat 패키지 1개 + endpoint 7개 추가 (6 + 이벤트 설문 조회 1, -621)

**Performance Goals**: 집계는 **원본에서 실시간**(C-04). 부스당 설문 1개·응답 수는 축제 규모(수십~수백)라 `GROUP BY` 한 번으로 끝난다. 느려지면 그때 캐시를 얹는다 — 지금 캐시를 두면 SC-001(집계값이 원본과 100% 일치)을 지킬 자리가 하나 늘어난다.

**Constraints**: 오류 봉투 5필드 단일(`docs/08` §1.3) · 페이지는 `page`/`size`(전역 규약) · 시각은 `Instant.now()` 인라인(Clock 빈 없음) · **응답의 모든 키는 항상 존재**(값 없으면 `null`·`0`) · 집계는 서버가 계산하고 원본 응답을 내보내지 않는다(FR-007) · 응답자 식별 정보를 어떤 응답에도 싣지 않는다(FR-009)

**Scale/Scope**: 부스당 설문 1행 + 이벤트당 설문 1행(-621). endpoint 7개 + 엔티티 5개·리포지토리 5개·서비스 3개·컨트롤러 1개 + 설정 record 1개 + 공용 부품 3곳 수정. Jira 7티켓(-130·-190·-131·-192·-132·-193·-191), MR 3개.

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 설계 후 재확인.*

| 조항 | 검증 | 결과 |
|---|---|---|
| 1. Source of Truth | 설문·응답·보상은 Spring PostgreSQL이 유일 기준. FE는 서버 집계값만 표시(FR-007) | PASS |
| 12. 게스트 비영속 | 게스트는 `users` 행이 없다 → 응답은 토큰 주체 키로 저장하고 **보상은 지급하지 않는다**(보상 설문은 403). 게스트에게 영구 자산이 생기지 않는다 | PASS |
| 16. 클라이언트 주장 불신 | `questionId`·`optionId`를 **설문 기준으로 재검증**한다. 남의 문항·남의 선택지·범위 밖 별점은 400. 응답자 식별자도 요청에서 받지 않고 토큰에서만 읽는다 | PASS |
| 20. Coin은 REST/DB 트랜잭션 | 지급은 `WalletService.credit` 한 경로, 응답 저장과 **같은 트랜잭션**, 원장 기록 + 멱등키. Realtime 이벤트로 바꾸지 않는다 | PASS |
| 24. 계약 변경 절차 | `contracts/survey-api.md`를 공동 정본으로 신설하고 Consumer(FE)에 #133으로 통보. 티켓 본문과 어긋난 2건(-190 422, -193 커서)은 Jira에 정정 통보 | PASS |
| 29. 기록 의무 | `docs/HDD/작업일지.md`·트러블슈팅 T-번호·Jira 1회 | PASS |
| 30. 미정 항목 임의 확정 금지 | C-01·C-02·C-03·C-05는 **기획 대기**로 두고 구현 기본값을 설정값·범위 밖 표기로 밝힌다. `docs/26` row 20·21에 등재. 리뷰 서명은 채우지 않는다 | PASS |

### 설계 후 재검증

- 게스트 중복 방지가 **토큰 단위**라는 한계를 계약 §6에 명시했다. 계정 없는 사람을 그 이상 식별할 방법이 없고, 그것이 C-05의 전제다.
- 익명(FR-009)은 컬럼을 만들지 않고 **결과에 응답자 필드를 아예 두지 않는 것**으로 충족한다. 토글을 만들면 "비익명 설문"이 응답자를 노출하는 경로가 생기고, 그 화면이 FE에 없다.
- Constitution 위반과 정당화가 필요한 복잡성은 없다.

## Project Structure

### Documentation (this feature)

```text
specs/010-survey/
├── spec.md                     # 공동 정본 (FR-001~013, C-01~C-09)
├── contracts/
│   └── survey-api.md           # 공동 정본 — endpoint 6개, FE Mapper 표
└── BE/
    ├── plan.md                 # 이 파일
    ├── research.md             # R-01~R-12
    ├── data-model.md           # V22 delta · 엔티티 5 · 불변식 · 검증 순서
    ├── quickstart.md           # 기준선 · 자동 검증 목록 · 손 왕복
    └── tasks.md                # $speckit-tasks 산출
```

### Source Code (repository root)

```text
backend/src/main/java/com/example/ssafesta/
├── survey/                                  ← 신설 (flat, project/ 관례)
│   ├── Survey.java  SurveyQuestion.java  SurveyOption.java
│   ├── SurveyResponse.java  SurveyAnswer.java
│   ├── SurveyRepository.java  SurveyQuestionRepository.java  SurveyOptionRepository.java
│   ├── SurveyResponseRepository.java  SurveyAnswerRepository.java
│   ├── SurveyQuestionType.java              enum 6종
│   ├── SurveyService.java                   upsert · findForEditor · findRun  (+ nested DTO)
│   ├── SurveyResponseService.java           submit  (+ 보상)
│   ├── SurveyResultService.java             results · textAnswers
│   ├── SurveyController.java                endpoint 6개
│   ├── SurveyProperties.java                @ConfigurationProperties("app.survey")
│   └── SurveyConfiguration.java             @EnableConfigurationProperties
├── booth/BoothAccessGuard.java              ← requireVisitorVisible 추가
├── booth/BoothRepository.java               ← findWithSharedLockById 추가
├── user/AccountDeletionService.java         ← 설문 6줄 키 수정 (R-09)
├── wallet/CoinReason.java                   ← SURVEY_REWARD 추가
├── common/ErrorCode.java                    ← 4개 추가
└── common/OpenApiConfiguration.java         ← tag("Survey") 추가

backend/src/main/resources/
├── db/migration/V22__survey_guest_and_rating_scale.sql
└── application.yml                          ← app.survey 블록

backend/db/rollback/
├── V22__rollback.sql
└── README.md                                ← 표 행 추가

backend/src/test/java/com/example/ssafesta/survey/
├── SurveyApiIntegrationTest.java            MR ①
├── SurveyResponseApiIntegrationTest.java    MR ②
└── SurveyResultApiIntegrationTest.java      MR ③
```

**Structure Decision**: 기존 Spring 모놀리스(`backend/`)에 flat 패키지 하나를 더한다. `project/`·`inventory/`·`game/`와 같은 모양이고 하위 디렉터리를 만들지 않는다 — 서비스가 자기 DTO를 nested record로 소유하는 것도 같다. 서비스를 셋으로 나눈 이유는 MR 경계와 트랜잭션 경계가 일치하기 때문이다(편집 / 제출·보상 / 조회 집계).

## Implementation Order

MR 3개. 각 MR이 develop에 닿을 때마다 FE가 붙일 수 있는 것이 늘어난다.

| MR | 브랜치 | Jira | 담는 것 |
|---|---|---|---|
| ① | `feat/S15P21A604-130-survey-domain` | -130 · -190 | SDD 산출물 5종 + 계약 · V22 + 롤백 · 설정·`ErrorCode`·태그 · 공용 3곳 · 엔티티·리포지토리 · `SurveyService` · endpoint 3개(§3·§4·§5) |
| ② | `feat/S15P21A604-131-survey-responses` | -131 · -192 | `SurveyResponse`·`SurveyAnswer` · `SurveyResponseService` · `CoinReason.SURVEY_REWARD` · endpoint 1개(§6) |
| ③ | `feat/S15P21A604-132-survey-results` | -132 · -193 · -191 | `SurveyResultService` · endpoint 2개(§7·§8) · 봉투·field 계약 테스트 · `docs/08` §9·§18 · `specs/README.md` · `docs/26` |

②는 ① 머지 후, ③은 ② 머지 후 develop에서 분기한다 — ②가 `Survey` 엔티티를, ③이 `SurveyResponse`를 읽으므로 순서가 강제된다.

## Complexity Tracking

> Constitution Check에 위반이 없어 비워 둔다.

## 범위 밖 — 하지 않는다

| 안 하는 것 | 왜 |
|---|---|
| 상태 전환 API(`OPEN`↔`CLOSED`) | FE Builder에 게시·마감 버튼이 없다(C-07). 마감은 `closesAt` 경과 |
| `DELETE /booths/{boothId}/survey` | FE에 리셋 화면이 없다. 응답이 쌓인 뒤 문항을 갈아엎을 길이 없는 것은 **알고 두는 천장**이고 코드에 `ponytail:` 주석으로 남긴다 |
| 지원서 제출자별 상세 조회 | **C-03 미결**(기획) + 티켓 없음. 주관식 항목에 `responseId`를 실어 열쇠만 남긴다 |
| 익명·1인1응답 토글 (FR-003) | 스키마 컬럼도 FE 토글도 없다. 항상 1인1응답·항상 익명으로 고정 |
| `booth_daily_metrics.survey_response_count` 갱신 | 대시보드 `S15P21A604-501` 몫 |
| `LayoutConfigResolver`에 `SURVEY_KIOSK` 검증 추가 | 설문 바인딩이 부스 기준(C-06)이라 `configId`를 쓰지 않는다. 지금은 `CONFIG_UNVERIFIED` 경고 그대로. 다중 설문이 되면 그때 |
| 고급 분석 (교차·추이·AI·내보내기) | **FR-013이 명시적으로 제외** |
| 집계 캐시 | C-04 — MVP는 실시간. 느려진 근거가 나오면 그때 |
| `spec.md` 리뷰 서명 | **C-01·02·03·05가 열려 있다** (009 선례) |
