# Research: 프로젝트 전시 (009 BE분)

**Date**: 2026-08-28 | **Spec**: [../spec.md](../spec.md) | **Plan**: [plan.md](plan.md)

`NEEDS CLARIFICATION` 0건. spec 009의 C-01·C-03~C-07이 GitLab #110에서 닫혔고, 남은 C-02는
착수를 막지 않는다(R-08). 아래는 그 확정들을 코드로 옮길 때 갈리는 지점의 근거다.

---

## R-01 — 링크 모델: V1 3칼럼 유지, `project_links` 신설 안 함

**Decision**: `projects.deploy_url` · `git_url` · `portfolio_url` 세 칼럼을 그대로 쓴다.
링크 목록·유형·표시명을 담는 별도 테이블을 만들지 않는다.

**Rationale**: C-05 확정. 결정적 근거는 **"기타 링크 + 표시명"을 요구한 화면이 존재하지
않는다**는 FE 실측이다(#110) — `festa-frontend/src`에서 project 관련 코드는 Layout 오브젝트
타입 `PROJECT_PANEL` 하나뿐이고 `specs/009`에 FE plan·tasks도 없다. 쓰는 사람이 없는 스키마를
추측으로 세우는 것이 된다. V1 스키마와 `docs/08` §5가 이미 3칼럼이라 마이그레이션도 0이다.

**Alternatives considered**:
- `project_links` 테이블 신설 — spec 009 Key Entities가 목록형을 적고 있었으나 **2026-08-28
  정본에서 삭제**했다(C-05). 요구사항 쪽이 낡은 것이지 스키마가 부족한 것이 아니었다
- 되돌리는 비용: 3칼럼 → 목록형은 데이터를 옮기는 마이그레이션 한 번이다. 반대 방향(안 쓰는
  목록형 테이블을 걷어내기)보다 싸므로 **지금 안 만드는 쪽이 회수 가능한 선택**이다

---

## R-02 — 부스당 1개를 무엇이 강제하는가: DB 유니크 인덱스 + 예외 번역 둘 다

**Decision**: `V14__project_one_per_booth.sql`로 `CREATE UNIQUE INDEX ux_projects_booth ON
projects(booth_id)`를 만들고, 서비스는 **사전 조회 검사**와 **`DataIntegrityViolationException`
번역**을 둘 다 둔다. 저장은 `saveAndFlush()`를 `try/catch` 안에서 호출한다.

**Rationale**: C-01은 불변식이지 UI 규칙이 아니다. 애플리케이션 검사만 두면 동시 `POST` 두 건이
각각 "없음"을 보고 둘 다 만든다 — `V7__booth_one_per_owner.sql`이 정확히 이 이유로 만들어졌고,
그 주석이 *"an assumption held only by application code"* 라고 같은 사고(T-110)를 적고 있다.

`save()`만 감싸면 안 되는 이유는 JPA의 flush 시점 때문이다. 쓰기 지연으로 INSERT가 **커밋
시점**에 나가면 유니크 위반이 `try/catch` 바깥에서 터져 번역을 우회하고 500이 나간다.
`BoothLeaseService.java:97-106`이 같은 이유로 `saveAndFlush`를 쓰고 주석에
*"The transaction is doomed, which is exactly what this case needs"* 라고 적어 뒀다.

사전 검사를 남겨 두는 이유는 **정상 경로의 메시지 품질**이다. 제약 위반만으로 판정하면 모든
중복이 예외 경로를 타고, 그 경로는 원인을 SQLState로만 안다.

**Alternatives considered**:
- 애플리케이션 검사만 — 경쟁 조건에서 두 행이 생기고 `findByBoothId`가
  `IncorrectResultSizeDataAccessException`으로 부스를 영구히 잠근다 (V7이 서술한 시나리오)
- 유니크 제약만 — 동작은 맞지만 흔한 중복이 예외 스택을 타고 로그가 시끄러워진다
- V7처럼 중복 정리 단계 포함 — **불필요하다.** `projects`는 쓰기 경로가 0건이라 행이 없다.
  그래도 인덱스 생성이 실패하면 마이그레이션이 멈추게 두는 편이 맞다 (조용히 지우지 않는다)

---

## R-03 — `PATCH`의 `null`: presence 추적 클래스, `record` 금지

**Decision**: 요청 DTO를 `record`가 아니라 필드별 setter가 `present` 비트를 세우는 클래스로
받는다. **키 누락 = 유지, 명시적 `null` = 삭제**, 본문 `{}` = 400.

**Rationale**: C-06 확정이고, 016에서 실제로 밟은 자리다. `record`는 `{}`와
`{"thumbnailUrl": null}`을 둘 다 `null`로 준다 — "안 건드림"과 "지움"이 같은 요청이 된다.
FE 직렬화 실수 하나가 등록된 값을 조용히 지운다. Jackson은 **키가 있을 때만 setter를 부르므로**
`present` 비트가 계약이 필요로 하는 구분을 정확히 담는다
(`BoothHomepageService.HomepageCommand` — 016에서 같은 구조).

`{}`를 400으로 막는 것도 016 선례다. 빈 본문을 no-op으로 통과시키면 "저장했다"는 200을 받고
아무 일도 안 일어난다.

**Alternatives considered**:
- `Optional<String>` 필드 — 타입은 살지만 직렬화 설정이 붙고, Jackson이 `Optional`을 필드로
  받는 것은 권장 형태가 아니다
- `JsonNullable`(openapi-jackson-databind-nullable) — **의존성이 없다.** 이 하나를 위해
  추가하지 않는다
- 전신 교체(PUT 의미) — FE에 "매번 전체를 보내라"는 제약을 건다. presence 방식은 FE가 어느
  쪽으로 보내도 동작하므로 제약이 없는 쪽을 고른다 (#110에서 통보)

**Test 함의**: `jsonPath().doesNotExist()`는 **명시적 null도 통과**한다(T-97). `null` 삭제를
검증할 때는 `value(nullValue())` + 키 존재를 함께 단언한다.

---

## R-04 — URL 검증기 추출: `common/HttpUrlValidator`, null은 통과

**Decision**: `BoothHomepageService.validated()`의 규칙을 `common/HttpUrlValidator`로 꺼내고
`BoothHomepageService`도 그것을 쓰게 바꾼다. 시그니처는
`validate(String value, String jsonField, String displayName)`.

**책임 경계**

| | 담당 |
|---|---|
| 서비스 | presence 판정, `{}` 거부, `name` 제약, 권한·만료 |
| 검증기 | **값의 형식만.** `null`이 왜 왔는지는 모른다 |

**null 계약**: `null`은 유효한 값이라 **그대로 반환하고 아무것도 검사하지 않는다.** 미등록
(POST에서 생략)이든 삭제(PATCH의 명시적 `null`)든 검증기 입장에서는 같다. 현재 홈페이지
구현도 같은 자리에서 같게 동작한다(`BoothHomepageService.java:70`). **빈 문자열만 거부한다** —
`""`는 "지우려다 잘못 보낸 것"이라 조용히 통과시키면 형식이 깨진 값이 저장된다.

**검증 순서** (`null` 통과 뒤, 어긋나면 안 되는 순서):

1. 빈 문자열 거부
2. 길이 ≤ 2048 (V1 컬럼 폭)
3. `new URI(...)` 파싱
4. `isAbsolute()` (= scheme 존재)
5. **scheme이 `http`/`https`인지 — host보다 먼저**
6. host 존재
7. 원문 그대로 반환 (trim·정규화 없음)

**5가 6보다 앞서는 이유**: `javascript:alert(1)`·`data:text/html,…`는 host가 없다. host를 먼저
보면 "형식이 올바르지 않습니다"로 끝나고 **스킴 위반이라는 실제 사유가 사용자에게 도달하지
않는다.** 두 순서가 막는 입력 집합은 같고 설명만 달라진다 — 그 설명이 T-24가 말하는 것이다.

**Rationale (추출하는 이유)**: URL 필드가 `thumbnailUrl`·`videoUrl`·`deployUrl`·`gitUrl`·
`portfolioUrl` **5개**다. 붙여넣으면 같은 규칙이 6벌(홈페이지 포함)이 되고, 언젠가 한 벌만
고쳐진다.

**표시명을 따로 받는 이유**: 홈페이지는 "홈페이지 주소는 http 또는 https로 시작해야 합니다."를
그대로 유지해야 한다(기존 통합 테스트가 문구를 본다). 프로젝트 필드는 자기 이름으로 말해야
한다("영상 주소는…", "배포 주소는…"). `jsonField`는 `ApiErrorDetail.field()`로,
`displayName`은 문장으로 간다.

**Alternatives considered**:
- Bean Validation 커스텀 애너테이션(`@HttpUrl`) — 순서 보장이 어렵고(제약 평가 순서는 미정의),
  사유별로 다른 문장을 내기 힘들다. 지금 방식은 **첫 위반에서 그 위반의 문장으로** 끊는다
- 정규식 한 방 — `javascript:`·유니코드 호스트·포트·IPv6에서 조용히 어긋난다. `java.net.URI`가
  이미 파서다
- 홈페이지는 그대로 두고 프로젝트만 새로 — 규칙 2벌. 추출의 안전망은 **기존 홈페이지 테스트
  19개**이고, 문구가 한 글자만 달라져도 그 테스트가 잡는다

---

## R-05 — 조회를 어디까지 110이 하는가: 편집자용 `GET`까지

**Decision**: `GET /api/v1/booths/{boothId}/projects`를 110에서 만들되 **편집자 전용, published
게이트 없음**. 방문자 노출과 좋아요 수는 S15P21A604-177이 맡는다.

**Rationale**: Jira 문구는 "등록·수정"이지만 `GET`이 하나도 없으면 **FE가 수정 폼 프리필을
만들 수 없다** — 저장은 되는데 다시 열 수 없는 화면이 된다. 016이 같은 문제를
`GET /booths/mine`(게이트 없음, 소유자 프리필)과 `GET /booths/{id}`(게이트 있음, 방문자)로
갈라 풀었고 그 선례를 그대로 탄다.

**목록 형태를 유지하는 이유**: C-01 파생 ⑵. 0~1개 배열을 돌려준다. 단수 객체로 바꾸면
`docs/08` §5의 계약 모양이 바뀌고, 나중에 상한이 2 이상이 되면 또 바꿔야 한다. 지금 배열이면
그때는 값만 늘어난다.

**Alternatives considered**:
- `POST`/`PATCH` echo만 — 티켓 경계는 깨끗하지만 177 머지 전까지 FE가 폼을 못 만든다
- 방문자 게이트까지 110 — 177이 좋아요 수만 남아 티켓이 비고, published 게이트 테스트가
  110으로 넘어와 범위가 커진다

---

## R-06 — 만료된 부스: 쓰기 거부, 읽기 허용

**Decision**: `POST`·`PATCH`는 유효 임대를 요구하고 없으면 `409 BOOTH_LEASE_EXPIRED`.
`GET`은 만료돼도 저장값을 돌려준다.

**Rationale**: 쓰기 거부는 facade·homepage와 같은 결이다 — 만료 부스는 아무에게도 보이지
않으므로 편집은 "안 보이는 것을 고치는 일"이 된다. 읽기 허용은 FR-008(임대가 만료돼도 데이터는
보존)의 관측 가능한 형태다. 보존해 놓고 읽을 수 없으면 보존을 확인할 방법이 없다.

C-04(원 소유자 귀속)와도 맞는다 — 재임대자는 **자기 부스**를 새로 받으므로 이전 임차인의
프로젝트에 애초에 닿지 않는다. 여기서 따로 막을 것이 없다.

---

## R-07 — 신설하는 것은 최상위 `code` 2개, `rule`은 0개

**Decision**: `ErrorCode`에 `PROJECT_NOT_FOUND`(404) · `PROJECT_ALREADY_EXISTS`(409)를 추가한다.
`errors[].rule`은 기존 `FIELD_INVALID` 하나만 쓴다.

**Rationale**: C-07 확정. 이 저장소는 `BOOTH_NOT_FOUND`·`GAME_NOT_FOUND`·`WALLET_NOT_FOUND`·
`CONFIG_NOT_FOUND`로 도메인별 코드를 두고 있고, FE가 분기할 값이라 계약이다.

**문서 반영 위치가 갈린다** — 이 둘은 봉투 **최상위 `code`**이므로 `docs/08` **§18 주요 오류
코드** 표에 넣는다. **§1.3-1 전역 `rule` 목록에 넣지 않는다** — 그 표는 `errors[].rule`
전용이고(`FIELD_INVALID` 한 줄), 섞으면 "이게 code인가 rule인가"를 endpoint마다 다시 판단하게
된다. #58이 봉투를 5필드 단일로 유지하기로 한 것과 같은 이유다.

`PROJECT_ALREADY_EXISTS`가 `409`인 것은 `ACTIVE_LEASE_LIMIT`(1인 1임대)와 같은 결이다 — 요청은
문법적으로 옳고 현재 상태와 충돌한다.

---

## R-08 — 미결 C-02(영상 제공자 목록)를 안고 착수하는 방법

**Decision**: `videoUrl`도 지금은 **D09 형식 검증만** 한다. 목록이 정해지면 그때 거부 규칙을
얹는다. **소급 삭제·숨김은 하지 않는다.**

**Rationale**: C-02는 제품 결정이라 BE가 정하면 헌법 30조 위반이다. 그리고 이 미결이 착수를
막지 않는 이유는 **추가되는 것이 거부뿐**이기 때문이다 — 나중에 좁히면 신규 등록·수정만 막힌다.

다만 **allowlist 밖 URL이 실제로 저장된다.** 그 데이터 처리 방침을 지금 못 박아 둔다:
**보존한다. 후속 제한은 신규 등록·수정에만 적용하고, 기존 비지원 URL을 조회에서 숨기지 않는다.**
숨기면 소유자가 자기 화면에서 값이 사라진 이유를 알 수 없다 — T-24와 같은 조용한 실패다.

서버 동작 원칙 자체는 #110에서 합의됐다: 좁히면 **서버도 등록 시점에 `400 VALIDATION_FAILED`로
거부**하고, 무제한이면 형식 검증만 한다. 통과시키고 화면에서만 안 나오는 조합은 만들지 않는다.

---

## R-09 — `name`·`description` 제약은 스키마가 이미 정해 뒀다

**Decision**: `name`은 필수·1~100자(V1 `VARCHAR(100) NOT NULL`), `description`은 선택·`TEXT`
(상한 없음). `PATCH`에서 `name: null`은 400.

**Rationale**: FR-001은 "이름·설명"만 말하고 길이를 말하지 않는다. 스키마가 이미 답을 갖고
있으므로 새로 정할 것이 아니다 — 상한을 임의로 더 낮추면 헌법 30조에 걸리고, `avatar_code`가
`VARCHAR(32)`로 잘려 T-24를 만든 것과 반대 방향의 같은 실수가 된다(헌법 23조).

`description`에 상한을 두지 않는 것은 `TEXT`가 그렇게 선언돼 있어서다. 남용이 관측되면 그때
정한다.

---

## R-11 — 직원 역할 게이트는 009가 걸지 않는다 (011 구현 때 가드 한 곳에서)

**Decision**: `BoothEditorGuard.requireEditor`를 **그대로** 쓴다 — 소유자이거나 `booth_staffs`에
행이 있으면 편집자다. 역할(`ADMIN`/`CONTENT_EDITOR`/`CONSULTANT`)로 거르지 않는다.

**충돌 사실**: spec 011 C-09가 **2026-08-28 확정**됐다(`cd1523e`, S15P21A604-136) —
*"Booth Studio의 Layout·Facade 편집은 `ADMIN`·`CONTENT_EDITOR`만 가능하며 `CONSULTANT`는 편집할 수
없다."* 그런데 현재 가드는 `staffs.existsByBoothIdAndUserId`만 보고 **`role`을 읽지 않는다.**
`booth_staffs.role VARCHAR(30) NOT NULL`은 V1부터 있으므로 **구현이 불가능해서가 아니다.**

**Rationale**:

1. **같은 구멍이 005·016에도 열려 있다.** 009만 역할을 보게 하면 같은 "편집자"가 endpoint마다
   다른 뜻이 된다 — Layout은 `CONSULTANT`가 고치는데 Project는 못 고치는 상태가 된다.
   그건 011 C-09가 의도한 것의 반대다
2. **가드가 한 곳에 있는 이유가 이것이다.** `BoothEditorGuard` 주석이 직접 적고 있다 —
   *"Spreading the same two-line check across three services is how one of them eventually forgets
   the staff branch."* 역할 분기를 서비스마다 붙이면 그 경고를 그대로 재현한다
3. **011은 아직 미구현이다.** `StaffInvitation*` 코드가 develop에 없다. 초대·수락·역할 배정
   경로가 서기 전에 역할 게이트만 먼저 걸면 검증할 데이터가 없다
4. **범위**: 가드를 고치면 005·016 회귀가 따라온다 — 110 티켓의 크기가 아니다 (헌법 28조)

**대가는 정직하게 적는다**: 011 구현 전까지 **`CONSULTANT`도 프로젝트를 편집할 수 있다.**
알고 여는 창이고, 011 구현 task가 닫는다.

**미확인 1건**: 011 C-09의 문면은 **`Layout·Facade`를 명시**하고 Project는 적지 않았다.
"Booth Studio 전체"를 뜻한 것인지 두 화면만인지는 011 담당(@정승욱)에게 **통보 이슈로 올려
확인 요청**했다. 어느 쪽이든 009의 결론(가드 한 곳에서 일괄)은 바뀌지 않는다 — 바뀌는 것은
011 구현 때 Project endpoint가 그 게이트에 포함되는지뿐이다.

**Alternatives considered**:
- 009에서 `ADMIN`/`CONTENT_EDITOR`만 허용 — 011 취지에 맞지만 005·016과 어긋나고, 011이 명시하지
  않은 화면에 BE가 역할 정책을 **임의 확정**하는 것이 된다 (헌법 30조)
- `BoothEditorGuard`에 역할 인자를 지금 추가 — 005·016 호출부와 테스트가 따라 움직인다.
  011 구현과 같이 해야 할 작업을 미리 쪼개는 것

---

## R-10 — `docs/sdd/parts/BE.md`의 "CRUD + S3"는 낡았다

**Observation**: `docs/sdd/parts/BE.md:18`이 009를 `CRUD + S3`로 적고 있다. C-03이 **업로드
미지원·URL 참조**로 닫았으므로 S3는 이 spec의 범위가 아니다.

**Decision**: 구현 커밋에서 그 줄을 고친다. 억지로 지금 고치지 않는 이유는 브리프가 여러 spec을
한 표로 담고 있어 단독 수정이 diff를 흩기 때문이다 — 계약 문서(`docs/08`)를 손볼 때 같이 간다.

**함의**: 이 낡은 한 줄이 `/speckit-tasks`에서 "S3 업로드" task를 만들 수 있다. `tasks.md`
서두 금지 목록에 대표 이미지 업로드를 박아 두는 이유다.
