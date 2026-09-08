# Research: 설문 (010 BE분)

**Spec**: [../spec.md](../spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-09-07

각 항목은 **무엇을 정했는지 · 왜 · 무엇을 기각했는지**다. 저장소 안의 선례를 근거로 쓴다.

---

## R-01 — 부스당 설문 1개: DB 유니크 인덱스 + 예외 번역 둘 다

**결정**: `V22`에 `CREATE UNIQUE INDEX ux_surveys_booth ON surveys(booth_id)`. 서비스는 `findByBoothId`로 먼저 보고, 없으면 만들면서 `saveAndFlush`를 `try/catch`로 감싸 제약 위반을 번역한다.

**왜 둘 다**: 애플리케이션 검사만 두면 동시 `PUT` 두 건이 각각 "없음"을 보고 둘 다 만든다. 인덱스만 두면 사용자가 `500`을 본다.

**선례**: `V7__booth_one_per_owner.sql`(부스 1인 1개), `V14__project_one_per_booth.sql`(프로젝트 부스당 1개) + `ProjectService.create:63-79`의 `try { saveAndFlush } catch (DataIntegrityViolationException) { ConstraintViolations.isViolationOf(ex, "ux_projects_booth") → 409 }`. `ConstraintViolations.nameOf`가 `null`이면 **번역하지 않고 다시 던진다**(`common/ConstraintViolations.java:29-33` — "guessing turns an unexplained failure into a confidently wrong answer").

**upsert라 409가 아니다**: 프로젝트는 `POST`라 "이미 있음"이 409였지만 설문은 `PUT` upsert이므로 경합에서 진 쪽은 **그 행을 다시 읽어 갱신**한다. 사용자에게는 두 저장이 순서대로 성공한 것으로 보인다 — 그게 `PUT`의 뜻이다.

**기각**: `booth_id`를 PK로 쓰기 — `surveys.id`를 참조하는 자식 4테이블이 이미 V1에 있어 변경 범위가 스키마 전체로 번진다.

---

## R-02 — 게스트 응답: nullable + 게스트 키 + 부분 유니크 2개 (C-05)

**문제**: V1이 `respondent_user_id BIGINT NOT NULL REFERENCES users(id)` + `UNIQUE(survey_id, respondent_user_id)`다. 게스트는 `users` 행이 없으므로(헌법 12조 — 비영속) **게스트 응답이 구조적으로 불가능**하다. 코드로 우회할 수 없다.

**결정**:
```sql
ALTER TABLE survey_responses ALTER COLUMN respondent_user_id DROP NOT NULL;
ALTER TABLE survey_responses ADD COLUMN respondent_guest_key VARCHAR(100);
ALTER TABLE survey_responses DROP CONSTRAINT survey_responses_survey_id_respondent_user_id_key;
ALTER TABLE survey_responses ADD CONSTRAINT ck_survey_responses_respondent
  CHECK ((respondent_user_id IS NULL) <> (respondent_guest_key IS NULL));
CREATE UNIQUE INDEX ux_survey_responses_member ON survey_responses(survey_id, respondent_user_id)
  WHERE respondent_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_survey_responses_guest ON survey_responses(survey_id, respondent_guest_key)
  WHERE respondent_guest_key IS NOT NULL;
```

**왜 xor CHECK**: 둘 다 `NULL`인 행은 응답자가 없는 응답이고, 둘 다 채운 행은 한 응답에 주인이 둘이다. 어느 쪽도 의미가 없어 DB가 거부해야 한다.

**왜 부분 유니크 2개**: 일반 유니크는 `NULL`을 서로 다른 값으로 보므로 `UNIQUE(survey_id, respondent_user_id)`만 남기면 게스트 응답 전체가 중복 검사를 빠져나간다. 두 축을 각각 걸어야 1인 1응답이 양쪽에 성립한다.

**제약 이름 확인**: V1이 자동 명명한 이름은 `survey_responses_survey_id_respondent_user_id_key`다 — 로컬 DB(V21 상태)의 `pg_constraint`에서 직접 확인했다. 추측이 아니다. V21도 기본 명명된 FK를 같은 방식으로 drop한 선례가 있다.

**게스트 키의 값**: 접속 토큰의 주체(`AccessTokenService:22` — `"guest:" + UUID.randomUUID()`, 42자). `VARCHAR(100)`에 들어간다.

**한계 — 감수한다**: 게스트 토큰은 **발급마다 새 주체**이고 게스트 refresh가 없다(`GuestAuthController`). 브라우저를 새로 열면 다른 사람으로 한 번 더 답할 수 있다. 계정 없는 사람을 그 이상 식별할 방법이 없고, 그것을 감수하는 것이 C-05의 전제다. 계약 §6과 코드 주석에 적는다.

**보상은 막는다**: 게스트는 지갑이 없어 `credit`이 `WALLET_NOT_FOUND`로 실패한다. 실패를 기다리지 않고 **`rewardCoin > 0`이면 제출 자체를 `403 MEMBER_ONLY`로 거부**한다 — 받지 못할 보상을 걸어 둔 설문을 시키고 나서 빈손으로 돌려보내지 않는다. `run` 응답에 `rewardCoin`을 실어 화면이 제출 전에 안내할 수 있게 한다.

**기각**:
- 게스트 전면 차단 — FE 논거대로 헌법 12조가 묶는 것은 **보상 지급**이지 응답 자체가 아니고, 게스트가 대다수인 09-08 테스트에서 설문 동선이 통째로 사라진다.
- `users`에 게스트 행 만들기 — 헌법 12조 정면 위반. 탈퇴·닉네임 유니크·지갑까지 전부 딸려온다.
- 쿠키·IP 기반 식별 — 개인정보를 새로 만드는 쪽이고 D11 정신과 어긋난다.

---

## R-03 — 문항 교체: 평면 엔티티 + 벌크 삭제 → 삽입 (중첩 cascade 금지)

**결정**: `Survey`·`SurveyQuestion`·`SurveyOption`을 **평면 `Long` 참조**로 두고, `PUT`은 `options 삭제 → questions 삭제 → flush → saveAll` 순서로 교체한다.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("DELETE FROM SurveyOption o WHERE o.questionId IN :questionIds")
int deleteAllByQuestionIdIn(@Param("questionIds") List<Long> questionIds);
```

**왜 cascade가 아닌가**: `@OneToMany(cascade = ALL, orphanRemoval = true)`를 중첩하면 Hibernate가 `OrphanRemovalAction`은 삽입 전에 실행하지만 **cascade된 손자 삭제는 `EntityDeleteAction`으로 삽입 뒤로 밀린다.** 그러면 ① 지워질 문항의 옵션이 아직 남은 채 문항 DELETE가 나가 `survey_options.question_id` FK에 걸리거나 ② 새 문항 INSERT가 옛 문항이 점유한 `UNIQUE(survey_id, display_order)`에 걸린다. 순서가 Hibernate 내부 구현에 의존하는 설계는 테스트가 통과해도 버전이 올라가면 깨진다.

**저장소 근거**: `backend/src/main/java` 전체에 `@OneToMany`가 **0건**이다. `Project.java:23-24`가 그 규칙을 문장으로 적어 두었고, 모든 엔티티가 `Long boothId` 같은 평면 참조를 쓴다. `GameAssetRepository.deleteAllByGameId:110-112`가 벌크 삭제의 정확한 모양이다.

**`survey_answer_options`는 매핑하지 않는다**: 복합 PK(`answer_id, option_id`)만 있는 조인 테이블이라 `@IdClass`를 만들 값이 없다. `ProjectRepository`가 `project_likes`를 native `INSERT`/`DELETE`로 다루는 것과 같은 판단이다.

**부수 효과**: 문항 교체는 `questionId`가 바뀐다. 응답이 있으면 애초에 교체가 금지되므로(R-11) 기존 응답이 고아가 되는 경로는 없다.

---

## R-04 — 보상 지급 순서: 응답 flush 뒤, 같은 트랜잭션, catch 뒤에는 아무것도 없다

**결정**:
```java
@Transactional
public SubmitResult submit(...) {
    // 게이트 → 검증 → 사전 exists 검사
    SurveyResponse saved;
    try {
        saved = responses.saveAndFlush(response);
    } catch (DataIntegrityViolationException ex) {
        if (ConstraintViolations.isViolationOf(ex, "ux_survey_responses_")) {
            throw new ApiException(ErrorCode.SURVEY_ALREADY_RESPONDED);
        }
        throw ex;
    }
    // 답 저장
    // 보상 — 반드시 이 뒤
    int rewarded = 0;
    if (memberId != null && survey.getRewardCoin() > 0) {
        LedgerResult result = wallets.credit(new CoinCreditCommand(memberId, LedgerEntryType.REWARD,
                survey.getRewardCoin(), CoinReason.SURVEY_REWARD, "SURVEY",
                String.valueOf(survey.getId()), rewardKey(survey.getId(), memberId)));
        saved.linkReward(result.entryId());
        rewarded = survey.getRewardCoin();
    }
    return new SubmitResult(saved.getId(), rewarded);
}
```

**왜 순서가 강제되는가**: PostgreSQL은 제약 위반이 발생하면 **트랜잭션을 abort 상태로 둔다.** `GameAssetRepository:114-120`이 그 사실을 문장으로 기록해 두었다("in PostgreSQL that exception marks the transaction as aborted, so the caller could not generate another id and try again inside the same unit of work"). 따라서 중복 제출 catch **뒤에는 어떤 쿼리도 실행할 수 없고**, 지급은 flush가 성공한 뒤에만 가능하다.

**왜 같은 트랜잭션**: 헌법 20조 — 지급은 원장과 함께 원자적이어야 한다. `WalletService`의 모든 메서드가 `@Transactional` 기본 전파(`REQUIRED`)이고 `REQUIRES_NEW`는 `CoinReconciliationService`에만 있으므로, 호출자의 트랜잭션에 그대로 합류한다.

**멱등**: 키는 `SURVEY_REWARD:{surveyId}:{userId}`(100자 제한 안, `CoinReason` 40자 제한은 `reasonType`에만 적용). 기존 키 형식과 같은 결이다 — `DAILY_GRANT:{userId}:{KST date}`, `PURCHASE:{userId}:{itemId}`. `credit`이 이미 적용된 키를 만나면 원래 항목 id와 `alreadyApplied = true`를 돌려주므로 잔액이 두 번 늘지 않는다(`WalletService:175-178`). `survey_responses.reward_ledger_entry_id UNIQUE`가 두 번째 방어다.

**지갑이 없으면**: `credit`이 `WalletNotFoundException`(404 `WALLET_NOT_FOUND`)을 던진다. 회원 지갑은 가입 트랜잭션에서 열리므로(`RegistrationService:57`) 이건 **깨진 상태**다 — 삼키지 않고 그대로 드러낸다(T-24).

**기각**: 보상을 별도 트랜잭션으로 분리 — 응답은 저장됐는데 지급이 실패한 상태가 생기고, 재시도할 주체가 없다. `@Async` 지급 — 같은 이유 + 응답에 `rewardedCoin`을 실을 수 없다.

---

## R-05 — `PUT`의 누락 필드: `PresenceField`, `record` 금지 (C-07)

**결정**: `SurveyCommand`를 `record`가 아닌 **클래스**로 두고 `description`·`rewardCoin`·`closesAt`을 `PresenceField<T>`로 받는다. `title`·`questions`는 필수라 일반 필드다.

**왜**: FE `saveDraft(draft)`가 보내는 것은 `{title, questions}` 뿐이다(`entities/survey/api.port.ts` — `SurveyDraftVM`에 `description`·`rewardCoin`·`closesAt`이 없다). 이 셋을 일반 필드로 받으면 `null`이 도착해 **Builder 저장마다 보상이 0으로 지워진다.** 운영자가 Swagger로 5코인을 걸어 두어도 FE 자동 저장 한 번에 사라진다.

**선례**: `common/PresenceField.java` 주석이 그 자리를 정확히 적어 두었다 — "`{}`와 `{"videoUrl": null}` 둘 다 `null`로 도착해 '그대로 둬'와 '지워'가 같은 요청이 된다. 016이 그 붕괴를 한 번 배포했다(T-97)." `ProjectService.ProjectCommand:312-347`이 같은 모양이다.

**규칙**: 키 부재 = 유지, 명시적 `null` = 비움(`description`·`closesAt`), `rewardCoin`은 `null`을 0으로 읽지 않고 **유지**로 읽는다.

---

## R-06 — 신설하는 것은 최상위 `code` 4개, 예외 클래스는 0개

**결정**: `ErrorCode`에 `SURVEY_NOT_FOUND`(404) · `SURVEY_CLOSED`(409) · `SURVEY_ALREADY_RESPONDED`(409) · `SURVEY_LOCKED`(409)를 더하고, 던질 때는 `throw new ApiException(ErrorCode.SURVEY_CLOSED)` 인라인. **`SurveyNotFoundException` 같은 클래스를 만들지 않는다.**

**왜**: `WorldSessionService:80,88,95`가 이미 그 방식이다. 예외 클래스는 ① 생성자에 식별자를 담아 메시지를 만들거나 ② 여러 곳에서 같은 조합을 던질 때 값이 있는데, 설문은 둘 다 아니다. `DomainExceptionEnvelopeTest`가 "모든 `*Exception`은 `ApiException`을 상속한다"를 검사하므로 클래스를 만들면 파일 4개 + 그 검사 대상이 늘어난다.

**`SURVEY_CLOSED`·`SURVEY_ALREADY_RESPONDED`는 이미 예약돼 있다**: `docs/08` §18:1230-1231에 코드와 뜻이 적혀 있다. 새 어휘가 아니라 예약된 어휘를 구현하는 것이다. `SURVEY_NOT_FOUND`·`SURVEY_LOCKED`는 신설이므로 `docs/08` §18에 행을 더한다.

**한글 기본 메시지 필수**: `ErrorEnvelopeIntegrationTest`가 기본 메시지의 한글을 검사한다.

---

## R-07 — `requireVisitorVisible`을 `BoothAccessGuard`로 승격 (4번째 복제를 만들지 않는다)

**문제**: 방문자 게이트(부스 존재 → 유효 임대 → 게시됨)가 이미 **세 곳에 복제**돼 있다 — `ProjectService.requireVisitorVisible:180-192`(private), `BoothQueryService.findPublicBooth`, `BoothLayoutQueryService`. `BoothAccessGuard` 주석이 그 셋을 이름으로 열거해 두었다.

**결정**: `BoothAccessGuard`에 `public Booth requireVisitorVisible(Long boothId)`를 추가하고 설문이 그것을 쓴다. 기존 세 곳 리팩터링은 **하지 않는다** — 이 티켓의 범위가 아니고, 동결된 동작을 건드리면 회귀 위험만 늘어난다.

**왜 승격인가**: 네 번째 복제는 **임대 검사 한 줄을 빠뜨리는 쪽**이다. `ProjectService:182-184`가 그 한 줄에 주석을 달아 두었다("이 줄을 지우면 정상 경로가 전부 초록인 채로 `expiredBoothIsConflictEvenWhenItWasPublished` 하나만 빨개진다"). 가드에 두면 그 한 줄이 한 곳에만 존재한다.

**순서가 계약이다**: 부스 없음(404) → 임대 만료(409) → 미게시(404). 권한 없는 사람에게 부스가 살아 있는지 알려주지 않기 위한 순서이고, Project와 같아야 FE가 같은 파서를 쓴다.

---

## R-08 — `PUT`/`POST` 경합: 부스 행 락

**문제**: `PUT`이 문항을 지우는 동시에 `POST`가 그 문항에 답을 넣으면 `survey_answers.question_id` FK(`ON DELETE` 없음)에 걸려 어느 쪽이든 `500`이 난다. 사전 `exists` 검사는 check-then-act라 경합을 막지 못한다.

**결정**: `PUT`은 `booths.findWithLockById(boothId)`(기존 `PESSIMISTIC_WRITE`), `POST`는 신설 `findWithSharedLockById`(`PESSIMISTIC_READ`)를 잡는다. 응답자끼리는 공유 락이라 직렬화되지 않는다.

**왜 부스 행인가**: `findWithLockById` 주석이 그 이유를 적어 두었다 — "The booth row is the natural thing to lock: both operations are scoped to one booth, and nothing else contends for it at publish frequency." 설문도 부스 단위다.

**락 순서**: 부스 → 지갑. 임대(`BoothLeaseService`)는 지갑 → 자기 부스라 순서가 겹치는 조합은 "자기 부스 임대 중에 자기 설문에 응답"뿐이고, 그건 발생하지 않는다.

**보조 방어**: 락을 쓰더라도 `survey_answers_question_id_fkey`·`survey_answer_options_option_id_fkey` 위반은 `ConstraintViolations`로 번역해 `409`로 돌린다 — 락이 커버하지 못하는 경로가 남았을 때 `500`이 아니라 사유가 나가야 한다.

---

## R-09 — `AccountDeletionService`의 설문 6줄이 지금 틀려 있다

**발견**: `user/AccountDeletionService.java:16-21`이 설문 그래프를 **`surveys WHERE created_by_user_id = ?`**로 지운다. 그런데 같은 파일의 다른 부스 콘텐츠 전부는 **`booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)`**로 지운다(프로젝트·AI 문서·레이아웃·메트릭·방문 이벤트).

**왜 결함인가**: `requireEditor`는 소유자와 **스태프**를 함께 허용한다. 스태프가 `PUT`으로 설문을 만들면 `created_by_user_id = 스태프`가 된다. 그러면

- 스태프가 탈퇴 → **부스 소유자의 설문과 응답 전부가 삭제된다** (남의 데이터 유실)
- 소유자가 탈퇴 → `surveys` 행이 남고 `DELETE FROM booths`가 `surveys_booth_id_fkey`에 걸려 **탈퇴가 500으로 실패한다**

지금은 쓰기 경로가 0개라 잠재 결함이고, **MR ①이 머지되는 순간 살아난다.**

**결정 (둘 다 한다)**:
1. 삭제 SQL의 설문 6줄을 `booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)` 키로 바꾸고, 응답은 `OR respondent_user_id = ?`를 유지한다(내가 남의 부스 설문에 답한 응답도 지워져야 한다).
2. `created_by_user_id`에 **항상 부스 소유자 id를 쓴다**(`Booth.getOwnerUserId()`). 읽는 곳이 없는 컬럼이고, 소유자로 고정하면 두 키가 같은 것을 가리킨다.

**검증**: "스태프가 만든 설문이 있는 부스의 소유자가 탈퇴한다" 통합 테스트 1건. 지금 코드로는 실패하고 수정 후 통과한다.

---

## R-10 — `docs/09`가 아니라 `V1`이 정본이다

**충돌**: `docs/09_DB_ERD_DB_설계서.md` §16~§18이 `type`·`title`·`required`·`order_no`·`text_value`·`numeric_value`·`selected_option_ids JSONB`를 적어 두었지만, `V1__initial_schema.sql:121-142`의 실제 컬럼은 `question_type`·`question_text`·`is_required`·`display_order`·`text_answer`·`rating_value` + **조인 테이블 `survey_answer_options`**다.

**결정**: **V1을 따른다.** 마이그레이션이 실제로 적용된 스키마이고 `ddl-auto: validate`가 엔티티를 그것에 맞춰 검사한다. `docs/09`는 낡았고 이 티켓에서 고치지 않는다 — 문서 정합화는 별 작업이고, 잘못 손대면 다른 spec의 근거가 흔들린다. `data-model.md`에 이 사실을 적어 다음 사람이 같은 혼동을 하지 않게 한다.

**부수**: `docs/09`는 `selected_option_ids`를 JSONB로 그려 두었지만 실제는 조인 테이블이므로 **선택지별 집계가 `GROUP BY`로 끝난다**. JSONB였다면 집계마다 배열을 펼쳐야 했다 — V1이 더 나은 쪽이다.

---

## R-11 — 응답 있는 설문: 문항 구조만 잠근다 (C-08)

**결정**: 응답이 1건 이상일 때 들어온 `questions`가 저장본과 **구조적으로 같으면** 제목·설명·보상·마감만 갱신하고 200, 다르면 **409 `SURVEY_LOCKED`**. 비교 대상은 유형·문구·필수·`scale`·선택지 라벨과 순서다.

**왜 전체 잠금이 아닌가**: 전체를 잠그면 **마감조차 걸 수 없다.** 설문을 그만 받고 싶은 운영자에게 방법이 없어지고, 그때 필요한 것은 상태 전환 API인데 FE에 버튼이 없다(C-07). 제목 오타 수정도 막힌다.

**왜 구조는 잠기는가**: 발명이 아니라 **DB가 이미 막는 것**이다. `survey_answers.question_id`와 `survey_answer_options.option_id`가 `ON DELETE` 없이 걸려 있어(V1, 로컬 DB에서 확인) 답이 달린 문항·선택지는 삭제되지 않는다. 잠금은 그 사실을 `500` 대신 사유로 돌려주는 것이다.

**왜 부분 수정을 더 잘게 쪼개지 않는가**: "문항을 추가하는 것만 허용"도 가능하지만, 집계 화면이 문항별로 응답 수가 다른 이유를 설명할 수 없게 된다("이 문항은 나중에 추가돼서 응답이 적습니다"를 어디에도 적을 수 없다). 지금은 잠그고, 필요해지면 문항에 `created_at`을 더해 여는 쪽이 정직하다.

**기각**: 응답을 지우고 문항을 바꾸게 하기 — SC-001(집계가 원본과 일치)의 원본이 사라진다. 새 설문 버전 만들기 — 부스당 1개(C-06)와 충돌하고 FE에 버전 개념이 없다.

---

## R-12 — SMALLINT 컬럼은 `short`, 설정은 미정 3개만

**SMALLINT**: `display_order`·`rating_value`(V1) + 신설 `rating_min`·`rating_max`가 `SMALLINT`다. `application.yml:17`이 `ddl-auto: validate`이므로 `Integer`로 매핑하면 **기동 시 스키마 검증이 실패한다**(`found int2, expecting integer`). `BoothSlot.java:33`이 `short floorNo`로 같은 자리를 이미 배웠다. 필수는 `short`, nullable은 `Short`.

**설정 vs 상수**: `app.survey`에는 **기획 미결 3개만** 둔다 — `max-questions`(C-01) · `max-options`(C-01) · `max-reward-coin`(C-02). 확정되면 yml 한 줄이다. 나머지(제목 200·문구 500·라벨 500·별점 1~10·단답 200·장문 2000)는 **컬럼 폭이나 UX에서 나온 상수**라 서비스 상수로 둔다 — `ProjectService:36`이 같은 방식이다. 열 개를 설정으로 빼면 "바뀌지 않는 값의 설정"이 되고, 그건 정확히 하지 말라는 것이다.

`SurveyProperties`는 `record` + compact constructor 검증(`GameProperties:19-30` 모양), `SurveyConfiguration`으로 등록(`BoothConfiguration` 모양). 값이 0 이하면 기동을 거부한다 — 조용히 0으로 뜨면 모든 저장이 400이 되고 원인이 설정이라는 것을 알 방법이 없다.
