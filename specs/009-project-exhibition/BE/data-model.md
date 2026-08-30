# Data Model: 프로젝트 전시 (009 BE분)

**Date**: 2026-08-28 | **Spec**: [../spec.md](../spec.md) | **Research**: [research.md](research.md)

---

## 1. 엔티티 — `Project` (테이블 `projects`, V1부터 존재)

**신규 테이블 없음.** V1이 만든 칼럼을 그대로 매핑한다. 마이그레이션은 유니크 인덱스 하나뿐이다(§4).

| 칼럼 (V1) | 타입 | Java | 규칙 |
|---|---|---|---|
| `id` | `BIGINT IDENTITY PK` | `Long id` | `@GeneratedValue(IDENTITY)` |
| `booth_id` | `BIGINT NOT NULL REFERENCES booths(id)` | `Long boothId` | `updatable = false`. **`@ManyToOne` 안 쓴다** — `BoothLease.java:33` 스타일 |
| `name` | `VARCHAR(100) NOT NULL` | `String name` | 필수, 1~100자 (R-09) |
| `description` | `TEXT` | `String description` | 선택, 상한 없음 (R-09) |
| `thumbnail_url` | `VARCHAR(2048)` | `String thumbnailUrl` | 선택, URL 검증 (§3) |
| `video_url` | `VARCHAR(2048)` | `String videoUrl` | 선택, URL 검증 |
| `deploy_url` | `VARCHAR(2048)` | `String deployUrl` | 선택, URL 검증 |
| `git_url` | `VARCHAR(2048)` | `String gitUrl` | 선택, URL 검증 |
| `portfolio_url` | `VARCHAR(2048)` | `String portfolioUrl` | 선택, URL 검증 |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now` | `Instant createdAt` | `updatable = false` |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT now` | `Instant updatedAt` | **애플리케이션이 갱신한다** (§5) |

**`project_links` 테이블은 만들지 않는다** (C-05, R-01). 링크는 위 3칼럼이다.

**`project_likes`는 이 작업 범위 밖이다.** V1에 테이블이 있지만 좋아요 수는
S15P21A604-177(방문자 조회) 몫이다.

### 불변식

| # | 불변식 | 무엇이 지키나 |
|:--:|---|---|
| I-1 | **부스당 프로젝트는 최대 1개** (C-01) | `ux_projects_booth` 유니크 인덱스 + 사전 조회 + 예외 번역 (§4, R-02) |
| I-2 | 프로젝트는 부스에 귀속되고 부스는 소유자에 귀속된다 (C-04) | `booth_id`가 `updatable = false`. 재임대자는 새 `booths` 행을 받으므로 자동 성립 |
| I-3 | 저장된 URL 바이트 = 반환된 URL 바이트 | 검증기가 trim·정규화를 하지 않는다 (R-04 7단계) |
| I-4 | 응답의 모든 키는 항상 존재하고, 값이 없으면 `null` | DTO가 `record`라 필드가 조건부로 사라지지 않는다 (C-03) |

---

## 2. 왕복 무손실

저장한 문자열이 그대로 돌아온다. 검증기는 **판정만 하고 값을 바꾸지 않는다** — `trim()`도,
소문자화도, trailing slash 정리도 없다.

```
입력  https://HtTpS-Case.example.com/a%20b?q=1#frag
저장  https://HtTpS-Case.example.com/a%20b?q=1#frag
반환  https://HtTpS-Case.example.com/a%20b?q=1#frag
```

스킴 판정만 대소문자를 무시한다(`equalsIgnoreCase`) — 판정이 관대한 것과 값을 바꾸는 것은
다른 일이다. 테스트가 스킴을 `HtTpS`로 쓰는 이유가 이것이다: 정규화가 끼어들면 이 케이스만
잡아낸다.

---

## 3. 검증 규칙 — 적용 순서대로

서비스가 아래 순서로 보고 **첫 위반에서 그 위반의 문장으로 끊는다.** 사유를 뭉뚱그리지 않는다
(T-24).

### #0 presence — URL 검증보다 먼저 (C-06)

`PATCH`에서 **키가 없으면 그 필드는 손대지 않는다.** 명시적 `null`만 삭제다. 요청 DTO는
`record`가 아니라 필드별 `present` 비트를 세우는 클래스다 (R-03).

| 본문 | 뜻 |
|---|---|
| `{"description": "새 설명"}` | `description`만 교체, 나머지 6필드 유지 |
| `{"description": null}` | `description` 삭제, 나머지 유지 |
| `{}` | **400** — 조용한 no-op을 만들지 않는다 |

`POST`는 전체 등록이라 presence가 의미 없다. 생략된 필드는 `null`로 저장한다.

### #1 `name`

| 순서 | 규칙 | 거부 문장 |
|:--:|---|---|
| 1 | `POST`에서 필수 / `PATCH`에서 `null` 금지 | `프로젝트 이름을 입력해 주세요.` |
| 2 | 공백만 있는 값 금지 | `프로젝트 이름을 입력해 주세요.` |
| 3 | ≤ 100자 | `프로젝트 이름이 너무 깁니다. (최대 100자)` |

### #2 URL 5종 — `common/HttpUrlValidator` (R-04)

`thumbnailUrl` · `videoUrl` · `deployUrl` · `gitUrl` · `portfolioUrl`에 같은 규칙을 각자의
필드명·표시명으로 적용한다.

| 순서 | 규칙 | 거부 문장 (`{표시명}` 치환) |
|:--:|---|---|
| 0 | **`null`이면 그대로 통과** — 검사하지 않는다 | — |
| 1 | 빈 문자열 금지 | `{표시명} 주소를 입력해 주세요.` |
| 2 | ≤ 2048자 | `{표시명} 주소가 너무 깁니다. (최대 2048자)` |
| 3 | `new URI(...)` 파싱 성공 | `{표시명} 주소 형식이 올바르지 않습니다.` |
| 4 | `isAbsolute()` (scheme 존재) | `{표시명} 주소 형식이 올바르지 않습니다.` |
| 5 | **scheme ∈ {`http`, `https`}** (대소문자 무시) | `{표시명} 주소는 http 또는 https로 시작해야 합니다.` |
| 5-1 | **`@`(userinfo) 금지** — ASCII·IDN 경로 공통 | `{표시명} 주소에 사용자 정보(@)를 넣을 수 없습니다.` |
| 6 | host 존재 (한글 도메인은 `IDN.toASCII` 로 판정, 저장은 원문) | `{표시명} 주소 형식이 올바르지 않습니다.` |
| 7 | 포트가 있으면 **1~65535** | `{표시명} 주소의 포트 번호가 올바르지 않습니다. (1~65535)` |

**5-1 이 IDN 분기보다 앞에 있어야 한다.** 초판은 IDN 경로 안에만 두어
`https://한글도메인.com@evil.example.com` 이 통과했다 — host 가 ASCII 라 원본이 그대로
파싱되고 조기 반환에 걸려 검사가 실행되지 않았다. 두 경로 중 하나에만 걸린 규칙은 규칙이 아니다.

**7이 따로 있는 이유**: `java.net.URI`는 포트를 `*DIGIT`로만 보므로 `https://x.com:99999`가
host까지 멀쩡히 파싱된다. 연결 불가능한 주소가 저장되면 방문자는 죽은 링크를 만나고
SC-002가 깨진다. `:abc`·`:-1`·오버플로는 authority가 registry-based가 돼 6에서 이미 걸린다.

**5가 6보다 앞이어야 한다.** `javascript:alert(1)`은 host가 없어, 순서가 뒤바뀌면 "형식이
올바르지 않습니다"로 끝나고 스킴 위반이라는 진짜 사유가 사용자에게 도달하지 않는다 (R-04).

표시명 대응:

| 필드 | 표시명 |
|---|---|
| `thumbnailUrl` | 대표 이미지 |
| `videoUrl` | 영상 |
| `deployUrl` | 배포 |
| `gitUrl` | 저장소 |
| `portfolioUrl` | 포트폴리오 |
| (`homepageUrl` — 016) | 홈페이지 |

⚠️ **016의 문장은 바이트 단위로 보존한다.** `BoothHomepageApiIntegrationTest` 19개가 문구를
본다 — 그게 이 추출의 안전망이다.

### #3 거부 봉투

전부 `400 VALIDATION_FAILED` + `errors[]`에 필드별 항목. `rule`은 항상 `FIELD_INVALID`이고
문제 필드는 `field`에 담는다 — `rule` 자리에 필드명을 넣지 않는다 (#58 §3, `docs/08` §1.3-1).

```json
{
  "code": "VALIDATION_FAILED",
  "message": "영상 주소는 http 또는 https로 시작해야 합니다.",
  "requestId": "req_a1b2c3d4",
  "errors": [
    { "rule": "FIELD_INVALID", "field": "videoUrl",
      "message": "영상 주소는 http 또는 https로 시작해야 합니다." }
  ],
  "warnings": []
}
```

---

## 4. 마이그레이션 — `V14__project_one_per_booth.sql`

```sql
CREATE UNIQUE INDEX ux_projects_booth ON projects(booth_id);
```

**중복 정리 단계 없음.** `projects`는 쓰기 경로가 0건이라 행이 없다. V7이 중복 병합·삭제를
했던 것은 이미 데이터가 있었기 때문이다.

기존 데이터에서 인덱스 생성이 실패하면 **마이그레이션이 멈추게 둔다** — 예상 밖의 행이 있다는
뜻이고, 조용히 지우는 것보다 사람이 보는 실패가 낫다 (V7이 같은 판단을 적어 뒀다).

인덱스는 **부분(partial) 아님**. 부스는 프로젝트를 하나만 가지며, 그것이 임대 상태와 무관하다
(C-04 — 콘텐츠는 소유자를 따라간다).

---

## 5. 시각 필드

| 필드 | 언제 |
|---|---|
| `created_at` | INSERT 시 DB 기본값. `updatable = false`로 잠근다 |
| `updated_at` | **값이 실제로 바뀐 `PATCH`에서 애플리케이션이 갱신** |

DB의 `DEFAULT CURRENT_TIMESTAMP`는 **INSERT 때만 먹는다.** UPDATE 트리거가 없으므로 코드가
갱신하지 않으면 `updated_at`이 생성 시각에 영원히 머문다. `BoothLease.java:46-55`가 같은
매핑 스타일이다.

**응답에는 넣지 않는다.** `docs/08` §5 필드 후보에 없고, 소비자가 없다. 필요해지면 그때 추가하는
쪽이 가산적이다.
