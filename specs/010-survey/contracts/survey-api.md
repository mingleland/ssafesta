# Survey API 계약 v1

**Spec**: [../spec.md](../spec.md) | **Date**: 2026-09-07 | **Jira**: S15P21A604-130 · -190 (생성·문항) · -131 · -192 (응답·보상) · -132 · -193 (결과·주관식)

> **공동 정본이다** (#43 — `spec.md`와 최상위 `contracts/`만 공동). BE 내부 근거는
> [BE/research.md](../BE/research.md)·[BE/data-model.md](../BE/data-model.md)에 있다.
>
> 이 문서는 `docs/08` §9를 **대체하지 않고 선행**한다. 구현과 함께 `docs/08` §9·§18을 이 내용으로
> 갱신한다 (009·016 선례).
>
> **FE는 이미 mock으로 화면을 완성했다** (`entities/survey/api.port.ts`, S15P21A604-368·-369·-133·-194).
> 이 계약은 그 Port 6호출에 1:1로 대응하며, 이름이 다른 자리는 §9에 Mapper 표로 모아 두었다.

---

## 0. 범위

| endpoint | 티켓 | FE Port | 이 문서 |
|---|---|---|---|
| `GET /api/v1/booths/{boothId}/survey` (편집자) | -130 | `getDraft()` | §3 |
| `PUT /api/v1/booths/{boothId}/survey` (편집자, upsert) | -130 · -190 | `saveDraft()` | §4 |
| `GET /api/v1/booths/{boothId}/survey/run` (방문자) | -130 | `getRun()` | §5 |
| `POST /api/v1/surveys/{surveyId}/responses` | -131 · -192 | `submitAnswers()` | §6 |
| `GET /api/v1/surveys/{surveyId}/results` (편집자) | -132 | `getResult()` | §7 |
| `GET /api/v1/surveys/{surveyId}/text-answers` (편집자) | -193 | `getTextAnswers()` | §8 |

**만들지 않는 것** — 이유를 함께 남긴다.

| 안 만드는 것 | 왜 |
|---|---|
| `POST /booths/{boothId}/surveys` (복수형 생성) · `PUT /surveys/{surveyId}` | **부스당 설문 1개**(C-06)라 목록도 개별 수정 경로도 필요 없다. `docs/08` §9의 네 stub은 §3·§4로 대체된다 |
| 상태 전환 API (`OPEN`↔`CLOSED`) | FE Builder에 게시·마감 버튼이 없다(C-07). 마감은 `closesAt` 경과로 판정한다 |
| `DELETE /booths/{boothId}/survey` | FE에 리셋 화면이 없다. 응답이 쌓인 뒤 문항을 갈아엎을 길이 없는 것은 **알고 두는 천장**이고, 리셋 버튼이 생기면 그때 가산한다 |
| 지원서 제출자별 상세 조회 | **C-03 미결**(기획). 주관식 항목에 `responseId`를 실어 열쇠만 남긴다 |
| 익명 여부·1인 1응답 토글 (FR-003) | 스키마에 컬럼이 없고 FE에 토글이 없다. **항상 1인 1응답, 결과는 항상 익명**으로 고정한다 — FR-009·SC-003이 구조적으로 충족된다 |

---

## 1. 공통

- 인증: `Authorization: Bearer <Access Token>`. 모든 endpoint가 인증을 요구한다.
- **편집 권한**: 부스 **소유자 또는 스태프** — `BoothAccessGuard.requireEditor`가 판정하며 facade·layout·project와 **정확히 같은 범위**다. 게스트는 `403 MEMBER_ONLY`.

> ⚠️ **직원 역할 게이트는 이 계약이 걸지 않는다.** spec 011 C-09가 *"Booth Studio 편집은 `ADMIN`·`CONTENT_EDITOR`만"*으로 확정됐지만 현재 `BoothAccessGuard`는 `booth_staffs` 행 존재만 보고 `role`을 읽지 않는다. 005·009·016도 같은 상태이고, **역할 게이트는 011 구현 시 가드 한 곳에서 일괄로 닫는다** (009 계약과 동일한 판단). 그때까지 `CONSULTANT`도 설문을 편집할 수 있다 — 알고 여는 창이다.

- **방문자 게이트** (§5·§6): 부스 존재 → **유효 임대** → **게시됨** 순서로 본다. `BoothAccessGuard.requireVisitorVisible` 한 곳이 판정하며 `ProjectService`·`BoothQueryService`·`BoothLayoutQueryService`가 쓰던 세 복제본과 같은 순서다. 순서가 계약이다 — 권한 없는 사람에게 부스가 살아 있는지 알려주지 않는다.
- 오류 봉투는 5필드 단일 (`docs/08` §1.3). 필드 오류의 `rule`은 항상 `FIELD_INVALID`이고 문제 필드는 `field`에 담는다.
- **응답의 모든 키는 항상 존재한다.** 값이 없으면 `null`(또는 수치는 `0`)이며 서버가 기본값을 채우지 않는다. `rewardedCoin`도 이 규칙을 따라 **보상이 없으면 `0`**이다 — FE가 "0과 키 부재를 구분하지 않는다"고 확인했다(#133).
- 시각은 전부 UTC ISO-8601 (`2026-09-08T09:00:00Z`).

### 문항 유형

wire 값은 **대문자**다. 저장소 전역이 대문자 enum이고 `docs/09` §16이 같은 어휘를 쓴다.

| 값 | spec 010 FR-002 | 답 형태 | 부속 |
|---|---|---|---|
| `SINGLE_CHOICE` | 객관식 | `selectedOptionIds` 1개 | `options` 2개 이상 |
| `MULTIPLE_CHOICE` | 복수선택 | `selectedOptionIds` 1개 이상, 중복 없음 | `options` 2개 이상 |
| `RATING` | 별점 | `rating` 정수, `scale` 범위 안 | `scale {min, max}` |
| `SHORT_TEXT` | 단답 | `text` ≤ 200자 | — |
| `LONG_TEXT` | 장문 | `text` ≤ 2,000자 | — |
| `APPLICATION` | 지원서 | `text` ≤ 2,000자 | — |

`APPLICATION`은 저장·검증이 `LONG_TEXT`와 같다. C-03이 확정되면 제출자별 조회가 붙는 자리이고, 그 열쇠로 §8이 `responseId`를 싣는다.

### 오류 코드

| code | HTTP | 언제 |
|---|---|---|
| `SURVEY_NOT_FOUND` | 404 | 부스에 설문이 없다 / `surveyId`가 없다 |
| `SURVEY_CLOSED` | 409 | 마감된 설문에 제출 |
| `SURVEY_ALREADY_RESPONDED` | 409 | 같은 회원 또는 같은 게스트가 재제출 |
| `SURVEY_LOCKED` | 409 | 응답이 있는 설문의 **문항 구조**를 바꾸려 했다 |
| `VALIDATION_FAILED` | 400 | 필드 위반. `errors[0].field`가 문제 필드다 |
| `MEMBER_ONLY` | 403 | 게스트가 편집 API / 게스트가 **보상 있는** 설문에 제출 |
| `BOOTH_EDITOR_FORBIDDEN` | 403 | 남의 부스 (FR-010·FR-011·SC-004) |
| `BOOTH_NOT_FOUND` | 404 | 그런 부스가 없다 |
| `BOOTH_LEASE_EXPIRED` | 409 | 임대 만료 — 쓰기와 방문자 경로 |
| `LAYOUT_NOT_PUBLISHED` | 404 | 게시본이 없는 부스의 방문자 경로 |
| `WALLET_NOT_FOUND` | 404 | 회원인데 지갑이 없다 — 가입 트랜잭션에서 열리므로 **깨진 상태**다. 조용히 넘기지 않는다 |

---

## 2. Survey 표현

```json
{
  "surveyId": 12,
  "boothId": 7,
  "title": "A604 부스 설문",
  "description": "참여해 주시면 코인을 드립니다",
  "rewardCoin": 5,
  "closesAt": null,
  "closed": false,
  "responseCount": 0,
  "questions": [
    {
      "questionId": 101,
      "type": "SINGLE_CHOICE",
      "prompt": "우리 부스를 어떻게 알았나요?",
      "required": true,
      "order": 0,
      "options": [
        { "optionId": 1001, "label": "월드를 돌아다니다가" },
        { "optionId": 1002, "label": "추천을 받고" }
      ],
      "scale": null
    },
    {
      "questionId": 102,
      "type": "RATING",
      "prompt": "만족도를 별점으로 남겨주세요",
      "required": true,
      "order": 1,
      "options": [],
      "scale": { "min": 1, "max": 5 }
    },
    {
      "questionId": 103,
      "type": "LONG_TEXT",
      "prompt": "개선할 점을 자유롭게 적어주세요",
      "required": false,
      "order": 2,
      "options": [],
      "scale": null
    }
  ]
}
```

- `order`는 0부터 연속이다. 배열 순서와 항상 같다 — FE가 둘 중 무엇을 믿어도 결과가 같다.
- 선택형이 아니면 `options`는 **빈 배열**, 별점이 아니면 `scale`은 `null`이다.
- `closed`는 서버 판정이다: `closesAt != null && closesAt <= now`. FE는 표시만 한다.
- `responseCount`는 잠금 여부를 화면이 미리 알 수 있게 싣는다 (§4).

---

## 3. `GET /api/v1/booths/{boothId}/survey` — 편집자 조회

FE `getDraft()`. Builder가 기존 설문을 불러오는 자리다.

- 권한: **편집자**. `requireEditor`만 — **유효 임대를 요구하지 않는다.** 임대가 끝난 소유자도 자기 설문을 되읽을 수 있어야 한다 (FR-011 "보존", 009 §3과 같은 판단).
- 200: §2의 Survey 표현.
- 404 `SURVEY_NOT_FOUND`: 아직 만들지 않았다. **오류가 아니라 정상 상태**다 — FE Port가 `null`을 "아직 만든 설문이 없다"로 읽으므로 어댑터에서 404를 `null`로 옮긴다.
- 403 `BOOTH_EDITOR_FORBIDDEN` / 404 `BOOTH_NOT_FOUND` / 403 `MEMBER_ONLY`(게스트).

---

## 4. `PUT /api/v1/booths/{boothId}/survey` — 생성·수정 (upsert)

FE `saveDraft(draft)`. 없으면 만들고 있으면 갱신하며 **둘 다 200**이다 — FE는 "저장"만 알고 생성인지 수정인지 몰라도 된다.

- 권한: **편집자 + 유효 임대** (`requireActiveEditor`). 만료된 부스에는 쓰지 않는다 (409 `BOOTH_LEASE_EXPIRED`).
- **저장하면 공개다** (C-07). FE Builder에 게시 버튼이 없으므로 저장본이 곧 방문자에게 보이는 설문이다. Builder 검증(제목·문항 비공백·선택지 2개 이상)을 통과한 것만 오므로 반쯤 만든 설문이 노출될 일은 없다.

### 요청

```json
{
  "title": "A604 부스 설문",
  "description": "참여해 주시면 코인을 드립니다",
  "rewardCoin": 5,
  "closesAt": "2026-09-08T09:00:00Z",
  "questions": [
    { "type": "SINGLE_CHOICE", "prompt": "우리 부스를 어떻게 알았나요?", "required": true,
      "options": [{ "label": "월드를 돌아다니다가" }, { "label": "추천을 받고" }] },
    { "type": "RATING", "prompt": "만족도", "required": true, "scale": { "min": 1, "max": 5 } },
    { "type": "LONG_TEXT", "prompt": "개선할 점", "required": false }
  ]
}
```

- `title`·`questions`는 **필수**다.
- `description`·`rewardCoin`·`closesAt`은 **키 존재 여부로 판정한다** — 키가 없으면 **기존 값을 유지**하고, 명시적 `null`이면 비운다. FE `saveDraft`는 `{title, questions}`만 보내므로 이 규칙이 없으면 저장마다 보상이 0으로 지워진다. 016이 밟은 자리다(T-97).
- 문항은 **전체 교체**다. `questionId`를 받지 않는다 — 순서 변경·삭제·추가가 한 번의 저장으로 표현되고, FE Builder가 로컬 id(`q-1`)로 편집하므로 서버 id를 왕복시킬 이유가 없다.

### 응답 있는 설문 (C-08)

응답이 1건 이상이면 **문항 구조는 잠긴다.**

- 들어온 `questions`가 저장본과 **구조적으로 같으면**(유형·문구·필수·`scale`·선택지 라벨과 순서) 제목·설명·보상·마감만 갱신하고 **200**.
- 하나라도 다르면 **409 `SURVEY_LOCKED`**.

전체를 잠그지 않는 이유: 그러면 마감(`closesAt`)조차 걸 수 없다. 구조만 잠그는 이유: `survey_answers.question_id` FK가 `ON DELETE` 없이 걸려 있어(V1) **문항 삭제는 DB가 이미 막는다** — 잠금은 발명이 아니라 그 사실을 사용자에게 사유로 돌려주는 것이다.

### 검증 — 400 `VALIDATION_FAILED`

`errors[0].field`가 문제 자리를 가리킨다. 문항 안이면 `questions[2].options` 같은 인덱스 경로다.

| field | 규칙 |
|---|---|
| `title` | 비공백, ≤ 200자 |
| `description` | ≤ 2,000자 (`null` 허용) |
| `rewardCoin` | 0 이상, **상한은 설정값**(기본 10 — C-02 미결) |
| `closesAt` | 미래여야 한다 |
| `questions` | 1개 이상, **상한은 설정값**(기본 30 — C-01 미결) |
| `questions[i].type` | 위 6개 중 하나 |
| `questions[i].prompt` | 비공백, ≤ 500자 |
| `questions[i].options` | 선택형은 2개 이상, **상한 설정값**(기본 10). 각 `label` 비공백·≤ 500자. **선택형이 아니면 비어 있어야 한다** |
| `questions[i].scale` | `RATING`은 필수, `1 ≤ min < max ≤ 10`. **`RATING`이 아니면 `null`이어야 한다** |

> C-01·C-02 상한은 기획 미결이라 `app.survey.max-questions`·`max-options`·`max-reward-coin` 설정으로 둔다. 정해지면 값만 바꾼다. **FE Builder에는 상한이 없어 BE가 처음 거는 제약**이므로, 초과 저장은 FE가 미리 막지 못하고 400으로 돌아온다.

---

## 5. `GET /api/v1/booths/{boothId}/survey/run` — 방문자 조회

FE `getRun(surveyId)`. Unity가 `{boothId, objectId}`만 보내고 FE가 부스 기준으로 설문을 여는 흐름에 맞춘 경로다 (`BOOTH_SURVEY_INTERACT`, S15P21A604-415).

- 권한: **회원 또는 게스트**. 방문자 게이트를 지난다 (§1).
- 200:

```json
{
  "surveyId": 12,
  "closed": false,
  "rewardCoin": 5,
  "questions": [ /* §2의 questions 와 같은 모양 */ ]
}
```

- **`surveyId`가 여기서 나온다.** FE가 쓰던 합성 id(`booth-{boothId}`)를 이 값으로 바꾸면 §6·§7·§8을 부를 수 있다.
- `closed: true`면 문항은 그대로 싣는다 — 화면이 "마감된 설문입니다"를 보여주되 무엇을 물었는지는 남는다. **제출은 §6이 409로 막는다.**
- `rewardCoin`을 싣는 이유: 게스트에게 "보상이 있어 회원만 참여할 수 있다"를 **제출 실패 전에** 보여줄 수 있어야 한다.
- 문항이 0개면 `questions: []`이고 200이다. FE가 "문항이 없습니다"를 그린다 (FR-012).
- 404 `SURVEY_NOT_FOUND` / 409 `BOOTH_LEASE_EXPIRED` / 404 `LAYOUT_NOT_PUBLISHED` / 404 `BOOTH_NOT_FOUND`.

---

## 6. `POST /api/v1/surveys/{surveyId}/responses` — 제출

FE `submitAnswers(surveyId, answers)`.

- 권한: **회원 또는 게스트**. 설문이 속한 부스에 방문자 게이트를 적용한다.
- **게스트 응답은 허용한다** (C-05, 기획 미결 · 구현 기본값). 단 `rewardCoin > 0`이면 **403 `MEMBER_ONLY`** — 게스트는 지갑이 없어 보상을 받을 수 없고(헌법 12조), 받지 못하는 사람에게 보상을 걸어 둔 설문을 시키지 않는다.
- **1인 1응답**: 회원은 `userId`, 게스트는 접속 토큰의 주체로 판정한다. 재제출은 **409 `SURVEY_ALREADY_RESPONDED`**.

> ⚠️ **게스트 세션 식별자는 세션이 끝나면 지워진다** (헌법 12조). 기준은 **그 응답을 낸 접속 토큰의 만료 시각**이다 — 제출 때 토큰의 `exp`를 함께 적어 두고(V23), 그 시각이 지나면 서버가 `respondent_guest_key`를 그 응답의 id에서 만든 값으로 바꾼다. **답과 집계는 그대로 남고 접속 토큰 주체만 사라진다.** 게스트 토큰은 갱신이 없으므로 그 시점이면 세션도 이미 죽어 있고, 1인 1응답이 약해지지 않는다.
>
> ⚠️ **게스트 중복 방지는 토큰 단위다.** 게스트 토큰은 발급마다 새 주체를 받으므로 브라우저를 새로 열면 다른 사람으로 응답할 수 있다. 계정이 없는 사람을 그 이상으로 식별할 방법이 없고, 그 한계를 감수하는 것이 C-05의 전제다.

### 요청

`docs/08` §9의 예시를 그대로 확장한다 — 배열 안에 문항별 답 하나씩, 유형에 맞는 키 하나만 채운다.

```json
{
  "answers": [
    { "questionId": 101, "selectedOptionIds": [1001] },
    { "questionId": 102, "rating": 4 },
    { "questionId": 103, "text": "무대 일정 안내가 더 잘 보이면 좋겠습니다" }
  ]
}
```

- 선택 문항을 답하지 않았으면 **배열에서 빼거나 빈 값으로 보낸다.** 빈 배열·공백 문자열은 "답하지 않음"으로 읽고 저장하지 않는다 — FE `isEmptyAnswer`와 같은 판정이다.

### 응답 201

```json
{ "responseId": 55, "rewardedCoin": 5 }
```

- `rewardedCoin`은 **실제 지급된 코인**이다. 보상이 없는 설문, 게스트 무보상 제출은 `0`. 키는 항상 있다.
- 지급은 응답 저장과 **같은 트랜잭션**이고 원장에 기록된다 (FR-005, 헌법 20조). 멱등키가 `설문 + 회원`이라 어떤 경로로 두 번 들어와도 지급은 한 번이다.

### 검증 — 400 `VALIDATION_FAILED`

| 상황 | `field` |
|---|---|
| 이 설문의 문항이 아닌 `questionId` | `answers[0].questionId` |
| 필수 문항을 답하지 않았다 | `answers` (누락된 `questionId`를 `message`에) |
| 유형에 맞지 않는 키 (`RATING`에 `text` 등) | `answers[1].rating` 등 실린 키 |
| 다른 문항의 `optionId` | `answers[0].selectedOptionIds` |
| `SINGLE_CHOICE`에 2개 이상 | `answers[0].selectedOptionIds` |
| `MULTIPLE_CHOICE`에 중복 `optionId` | `answers[0].selectedOptionIds` |
| `rating`이 `scale` 밖 | `answers[1].rating` |
| `text` 길이 초과 (단답 200 / 장문·지원서 2,000) | `answers[2].text` |

- 409 `SURVEY_CLOSED` — 마감. 403 `MEMBER_ONLY` — 게스트 × 보상. 404 `SURVEY_NOT_FOUND`.

---

## 7. `GET /api/v1/surveys/{surveyId}/results` — 결과

FE `getResult(surveyId)`. **집계는 서버가 계산한다** (FR-007) — 원본 응답은 나가지 않는다.

- 권한: **편집자**(설문이 속한 부스). §3과 같이 **유효 임대를 요구하지 않는다** — 임대가 끝나도 결과는 읽을 수 있어야 한다.
- 200:

```json
{
  "surveyId": 12,
  "totalResponses": 20,
  "firstRespondedAt": "2026-09-08T04:11:02Z",
  "lastRespondedAt": "2026-09-08T07:55:40Z",
  "perQuestion": [
    { "questionId": 101, "type": "SINGLE_CHOICE", "answeredCount": 18,
      "counts": [ { "optionId": 1001, "label": "월드를 돌아다니다가", "count": 11 },
                  { "optionId": 1002, "label": "추천을 받고", "count": 7 } ],
      "average": null, "distribution": [] },
    { "questionId": 102, "type": "RATING", "answeredCount": 20,
      "counts": [], "average": 4.2,
      "distribution": [ { "value": 1, "count": 0 }, { "value": 2, "count": 1 },
                        { "value": 3, "count": 2 }, { "value": 4, "count": 8 },
                        { "value": 5, "count": 9 } ] },
    { "questionId": 103, "type": "LONG_TEXT", "answeredCount": 12,
      "counts": [], "average": null, "distribution": [] }
  ],
  "textAnswers": {
    "content": [ { "responseId": 55, "questionId": 103, "text": "무대 일정 안내가…" } ],
    "page": 0, "size": 20, "totalElements": 12, "totalPages": 1
  }
}
```

- **`perQuestion`은 모든 문항을 싣는다** — 텍스트 유형도 `answeredCount`만 채워 들어간다. 빠뜨리면 화면이 "3문항 중 2개"만 그리게 되고, 그게 응답 0인지 문항 삭제인지 알 수 없다.
- **비율은 싣지 않는다.** `count / answeredCount`로 화면이 계산한다 — 복수선택은 합이 100%를 넘고(Edge Case) 그 사실이 수치에 그대로 드러나는 편이 옳다.
- `answeredCount`는 **그 문항에 답한 응답 수**다. `totalResponses`와 다를 수 있다 — 선택 문항을 건너뛴 사람이 있기 때문이다 (Edge Case "미응답 문항은 문항별 응답 수에서 제외").
- **응답 0건**: `totalResponses: 0`, `firstRespondedAt`·`lastRespondedAt`은 `null`, 모든 문항이 `answeredCount: 0`·`count: 0`·**`average: null`**, `textAnswers.content: []`. 0으로 나누지 않는다 (FR-012·SC-002).
- **응답자 식별 정보는 어떤 필드에도 없다** (FR-009·SC-003). `responseId`는 같은 사람의 답을 묶는 열쇠이지 사람의 이름이 아니다.
- 403 `BOOTH_EDITOR_FORBIDDEN` / 404 `SURVEY_NOT_FOUND`.

---

## 8. `GET /api/v1/surveys/{surveyId}/text-answers` — 주관식 페이지

FE `getTextAnswers(surveyId, page)`. §7의 `textAnswers`가 첫 페이지이고 그 다음을 이 endpoint로 넘긴다 (FR-008).

- 권한: **편집자**.
- 쿼리: `page`(0부터, 기본 0) · `size`(1~100, 기본 20) · `questionId`(선택 — 없으면 텍스트 3유형 전체).
- 200: §7의 `textAnswers`와 같은 페이지 모양.
- **정렬은 응답 id 오름차순으로 고정**이다. 새 응답은 항상 뒤에 붙으므로 페이지를 넘기는 중에 제출이 들어와도 **경계에서 중복·누락이 없다**.
- 400 `VALIDATION_FAILED`: `page` 음수, `size` 범위 밖, `questionId`가 이 설문 것이 아님.

---

## 9. FE 어댑터가 옮길 것

FE Port는 mock 기준으로 먼저 확정됐다. 실 어댑터에서 아래만 변환하면 VM은 그대로 쓴다.

| FE Port | 이 계약 | 변환 |
|---|---|---|
| `SurveyQuestionType` 소문자 (`single`) | 대문자 (`SINGLE_CHOICE`) | 6쌍 매핑 |
| `getRun(surveyId)` | `GET /booths/{boothId}/survey/run` | 부스 기준 호출. 응답의 `surveyId`를 저장해 이후 호출에 쓴다 — **합성 id `booth-{boothId}`는 여기서 사라진다** |
| `SurveyRunSnapshot.status: 'open'\|'closed'` | `closed: boolean` | `closed ? 'closed' : 'open'` |
| `submitAnswers → Promise<void>` | `201 { responseId, rewardedCoin }` | **반환 타입 변경 필요.** 완료 화면에 `rewardedCoin`을 표시 (0이면 표시 없음) |
| `answers: Record<string, SurveyAnswerValue>` | `{ answers: [...] }` 배열 | 맵 → 배열, `type` 판별자 → 유형별 키 |
| `SurveyTextAnswerPage {items, page, hasNext}` | `{content, page, size, totalElements, totalPages}` | `hasNext = page + 1 < totalPages`. ⚠️ **`items` 를 `string[]` 으로 두면 항목의 `questionId`·`responseId` 가 사라진다** — 텍스트 문항이 2개 이상이면 어느 질문의 답인지 복구할 수 없다. 문항별로 보여줄 계획이면 `?questionId=` 로 나눠 부르거나 `items` 를 객체 배열로 둔다 |
| `SurveyResultSnapshot` 에 시각 필드 없음 | `firstRespondedAt` · `lastRespondedAt` (응답 0건이면 `null`) | ⚠️ **spec US2 시나리오 1이 "전체 응답 수·최초·최근 응답 시각이 보인다" 를 요구한다** — Port 에 두 필드를 더해야 화면에 올릴 수 있다 |
| `SurveyQuestionAggregateVM` `kind: 'choice'\|'rating'` | `type` + 항상 있는 `counts`·`average`·`distribution` | `type`으로 `kind` 결정. 텍스트 유형은 VM에 없어 건너뛰거나 `answeredCount`만 쓴다 |
| `getDraft() → null` | `404 SURVEY_NOT_FOUND` | 404를 `null`로 |
| `saveDraft({title, questions})` | `PUT` 본문 | 그대로. `rewardCoin`·`closesAt`은 **키를 보내지 않으면 유지**된다 |

**FE에 화면이 없는 것 하나** — Builder에 `rewardCoin` 입력이 없다. 서버는 `-192`로 지급을 준비하지만 운영자가 값을 넣을 자리가 없어 제품 경로로는 보상이 걸리지 않는다. FE 티켓이 보이지 않아 #133에 알렸다.

---

## 10. 관련 문서

- 정본 명세: [../spec.md](../spec.md) — FR-001~FR-013, Clarifications C-01~C-09
- BE 근거: [../BE/plan.md](../BE/plan.md) · [../BE/research.md](../BE/research.md) · [../BE/data-model.md](../BE/data-model.md) · [../BE/quickstart.md](../BE/quickstart.md)
- 미결 결정: `docs/26_팀_결정_필요사항.md` row 20(게스트)·21(보상 상한)
- 논의: GitLab [#133](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/133)
