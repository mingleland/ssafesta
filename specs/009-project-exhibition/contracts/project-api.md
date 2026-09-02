# Project Exhibition API 계약 v1

**Spec**: [../spec.md](../spec.md) | **Date**: 2026-08-28 (§6 방문자 조회 추가 2026-08-31) | **Jira**: S15P21A604-110 (쓰기·편집자 조회) · S15P21A604-177 (방문자 조회)

> **공동 정본이다** (#43 — `spec.md`와 최상위 `contracts/`만 공동). BE 내부 근거는
> [BE/research.md](../BE/research.md)·[BE/data-model.md](../BE/data-model.md)에 있다.
>
> 이 문서는 `docs/08` §5를 **대체하지 않고 선행**한다. 구현과 함께 `docs/08` §5·§18을 이 내용으로
> 갱신한다 (016 선례 `fa50d91`).

---

## 0. 범위

| endpoint | 티켓 | 이 문서 |
|---|---|---|
| `POST /api/v1/booths/{boothId}/projects` | -110 | §2 |
| `GET /api/v1/booths/{boothId}/projects` (편집자) | -110 | §3 |
| `PATCH /api/v1/projects/{projectId}` | -110 | §4 |
| `GET /api/v1/booths/{boothId}/projects/published` (방문자) | -177 | §6 |
| `PUT`·`DELETE /api/v1/projects/{projectId}/like` | **-135** | §8 |
| ~~`GET /api/v1/projects/{projectId}`~~ | — | **만들지 않는다** (아래) |

> **`docs/08` §5에 있던 `GET /projects/{projectId}`(상세 조회)는 신설하지 않는다.**
> 부스당 프로젝트가 1개이므로(C-01) `GET /booths/{boothId}/projects`가 같은 값을 이미 준다 —
> 소유자는 자기 부스 번호를 알고, 방문자 경로는 -177이 부스 기준으로 설계한다. `projectId`로
> 직접 여는 화면이 생기면 그때 가산적으로 추가한다. `docs/08` §5를 갱신할 때 이 줄을 함께 옮긴다.

**부스당 프로젝트는 1개다** (C-01). 목록 endpoint는 그래도 **배열**을 돌려준다 — 0개 또는 1개.

---

## 1. 공통

- 인증: `Authorization: Bearer <Access Token>`. **회원만** — 게스트는 `403 MEMBER_ONLY` (헌법 12조)
- 편집 권한: 부스 **소유자 또는 스태프** — `BoothEditorGuard.requireEditor`가 판정하며
  facade·layout과 **정확히 같은 범위**다

> ⚠️ **직원 역할 게이트는 이 계약이 걸지 않는다.** spec 011 C-09(2026-08-28 확정)가
> *"Booth Studio의 Layout·Facade 편집은 `ADMIN`·`CONTENT_EDITOR`만, `CONSULTANT`는 불가"* 로
> 정했는데, 현재 `BoothEditorGuard`는 `booth_staffs`에 행이 있는지만 보고 **`role`을 읽지 않는다.**
> 005·016도 같은 상태다.
>
> **역할 게이트는 011 구현 시 `BoothEditorGuard` 한 곳에서 일괄로 닫는다.** 009만 따로 걸면
> 같은 "편집자"가 endpoint마다 다른 뜻이 되고, 가드를 한 곳에 둔 이유가 사라진다.
> 그때까지 `CONSULTANT`도 프로젝트를 편집할 수 있다 — **알고 여는 창이다.**
> 011 C-09가 Layout·Facade만 뜻하는지 Booth Studio 전체인지는 011 담당에게 확인 요청했다.
- 오류 봉투는 5필드 단일 (`docs/08` §1.3). 필드 오류의 `rule`은 항상 `FIELD_INVALID`이고
  문제 필드는 `field`에 담는다
- **응답의 모든 키는 항상 존재한다.** 값이 없으면 `null`이며 서버가 기본값을 채우지 않는다
  (C-03 — `avatarCode`와 같은 규칙)

### Project 표현

```json
{
  "projectId": 1,
  "name": "SSAFY FESTA",
  "description": "메타버스 축제 플랫폼",
  "thumbnailUrl": "https://cdn.example.com/thumb.png",
  "videoUrl": "https://youtu.be/xxxx",
  "deployUrl": "https://festa.example.com",
  "gitUrl": "https://lab.ssafy.com/team/festa",
  "portfolioUrl": null
}
```

`name` 외 모든 필드는 `null`일 수 있다.

### URL 필드 규칙 (5종 공통)

`thumbnailUrl` · `videoUrl` · `deployUrl` · `gitUrl` · `portfolioUrl`

- **`http` 또는 `https`만** (D09 — `spec 004 §D09`, `009 FR-004`). `javascript:`·`data:`·`file:` 거부
- 최대 2048자
- **형식 검증만 한다. 제공자 allowlist는 없다** — YouTube·GitHub 등으로 좁히지 않는다.
  좁히면 정상 배포·포트폴리오 URL이 거부돼 SC-002(링크 도달률 100%)를 스스로 깬다
- 저장 바이트 = 반환 바이트. **trim·소문자화·정규화 없음**
- **한글 도메인 허용.** 판정만 punycode(`IDN.toASCII`)로 하고 저장·반환은 원문 그대로다
- `null` 허용. `PATCH`에서 명시적 `null`은 **삭제**다
- **전각 구분자로 구조를 밀반입할 수 없다.** `＠`·`／`·`？`·`＃` 는 IDN 매핑 후 실제 구분자가
  되므로 거부한다. 다만 `。`(U+3002)는 IDN 이 `.` 로 매핑하는 **정당한 라벨 구분자**라 통과한다
- **`@`(userinfo)를 넣을 수 없다.** `https://user@host`·`https://oauth2:token@host` 는 `400` —
  이 필드는 공개 전시되므로 자격증명이 그대로 노출되고, 눈에는 `@` 앞이 먼저 보여 목적지를
  오인하게 만든다. 거부 문장은 `{표시명} 주소에 사용자 정보(@)를 넣을 수 없습니다.`
- **포트를 붙이려면 `1~65535`.** `https://x.com:99999`처럼 범위를 넘으면 `400` — 파서는
  받아주지만 연결이 안 되는 주소다
- **빈 문자열 `""`는 `400`이다.** 지우려면 `null`을 보내라 — `""`를 통과시키면 형식이 깨진 값이
  저장되고, 그건 "지웠다"고 믿는 화면과 어긋난다

> ⚠️ **`videoUrl`의 제공자 범위는 아직 미결이다** (C-02, 기획 대기). 목록이 정해지면 서버가
> **등록 시점에 `400 VALIDATION_FAILED`로 거부**하도록 이 문서를 갱신한다. 그 전까지는 형식
> 검증만 한다. **소급 처리 방침**: 목록 확정 전에 저장된 URL은 보존하고, 제한은 신규 등록·수정에만
> 적용하며, **기존 비지원 URL을 조회에서 숨기지 않는다** — 숨기면 소유자가 값이 사라진 이유를
> 알 수 없다 (T-24).

---

## 2. `POST /api/v1/booths/{boothId}/projects` — 등록

부스에 프로젝트를 새로 만든다. **부스당 1개**이므로 이미 있으면 거절한다 — 덮어쓰지 않는다.

### 요청

```json
{
  "name": "SSAFY FESTA",
  "description": "메타버스 축제 플랫폼",
  "thumbnailUrl": "https://cdn.example.com/thumb.png",
  "videoUrl": "https://youtu.be/xxxx",
  "deployUrl": "https://festa.example.com",
  "gitUrl": "https://lab.ssafy.com/team/festa",
  "portfolioUrl": null
}
```

`name`만 필수. **생략한 필드는 `null`로 저장된다.**

### 응답 — `201`

§1의 Project 표현.

### 실패

| 상태 | code | 언제 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `name` 누락·공백·101자 이상 / URL 5종 중 형식 위반. `errors[0] = { rule: "FIELD_INVALID", field, message }` |
| 401 | `UNAUTHORIZED` | 토큰 없음·만료 |
| 403 | `MEMBER_ONLY` | 게스트 |
| 403 | `BOOTH_EDITOR_FORBIDDEN` | 소유자·스태프 아님 |
| 404 | `BOOTH_NOT_FOUND` | 부스 없음 |
| 409 | `BOOTH_LEASE_EXPIRED` | 유효 임대 없음 — 만료 부스는 편집할 수 없다 |
| 409 | **`PROJECT_ALREADY_EXISTS`** | 이미 프로젝트가 있다. **수정은 `PATCH`** |

`PROJECT_ALREADY_EXISTS`는 동시 요청에서도 같은 응답이 나온다 — 유니크 제약 위반을 같은 코드로
번역한다.

---

## 3. `GET /api/v1/booths/{boothId}/projects` — 편집자 조회

소유자·스태프가 **자기 부스의 저장값**을 읽는다. 수정 폼 프리필용이다.

- **published 게이트를 걸지 않는다.** 미게시 부스의 소유자도 자기 값을 봐야 한다
  (016의 `GET /booths/mine`과 같은 이유)
- **만료 부스도 읽을 수 있다** (FR-008 — 데이터 보존). 막는 것은 쓰기뿐
- 방문자용 조회는 이 endpoint가 아니다 → **§6** (`S15P21A604-177`)

### 응답 — `200`

```json
{ "projects": [ { "projectId": 1, "name": "SSAFY FESTA", "…": "…" } ] }
```

프로젝트가 없으면:

```json
{ "projects": [] }
```

**빈 배열이지 404가 아니다.** "아직 안 만들었다"는 오류가 아니다.

### 실패

| 상태 | code | 언제 |
|---|---|---|
| 401 | `UNAUTHORIZED` | 토큰 없음·만료 |
| 403 | `MEMBER_ONLY` | 게스트 |
| 403 | `BOOTH_EDITOR_FORBIDDEN` | 소유자·스태프 아님 |
| 404 | `BOOTH_NOT_FOUND` | 부스 없음 |

---

## 4. `PATCH /api/v1/projects/{projectId}` — 수정

보낸 필드만 바꾼다.

### `null`의 의미 — 계약이다 (C-06)

| 본문 | 뜻 |
|---|---|
| 키 **누락** | 그 필드는 **손대지 않는다** |
| 키 있고 값 `null` | 그 필드를 **삭제**한다 |
| `{}` (빈 본문) | **400** — 조용한 no-op을 만들지 않는다 |

**FE를 제약하지 않는다** — 바뀐 필드만 보내도, 전체를 보내도 의도대로 동작한다.
다만 **"지운다"는 키를 빼지 말고 `null`을 명시해야 한다.** 키를 빼면 "안 건드림"이다.

`name`은 `NOT NULL`이라 `null`을 보내면 `400`이다.

```json
{ "videoUrl": null, "deployUrl": "https://new.example.com" }
```

→ 영상 주소 삭제, 배포 주소 교체, 나머지 5필드 유지.

### 응답 — `200`

§1의 Project 표현 (변경 후 전체).

### 실패

| 상태 | code | 언제 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 빈 본문 `{}` / `name: null` / `name` 길이 / URL 형식 위반 |
| 401 | `UNAUTHORIZED` | 토큰 없음·만료 |
| 403 | `MEMBER_ONLY` | 게스트 |
| 403 | `BOOTH_EDITOR_FORBIDDEN` | **그 프로젝트가 속한 부스**의 편집자가 아님 — 타 부스 프로젝트 수정 차단 |
| 404 | **`PROJECT_NOT_FOUND`** | 프로젝트 없음 |
| 409 | `BOOTH_LEASE_EXPIRED` | 유효 임대 없음 |

---

## 5. 신규 오류 코드 2건

봉투 **최상위 `code`**다. `errors[].rule`이 아니다 — `docs/08` §18(주요 오류 코드) 표에 넣고
§1.3-1(전역 rule) 표에는 넣지 않는다.

| code | HTTP | 뜻 |
|---|---|---|
| `PROJECT_NOT_FOUND` | 404 | 프로젝트 없음 |
| `PROJECT_ALREADY_EXISTS` | 409 | 이 부스에는 이미 프로젝트가 있다. 수정은 `PATCH` |

이 API가 내는 `rule`은 기존 `FIELD_INVALID` 하나뿐이다 — 전역 rule 표에 추가할 것이 없다.

---

## 6. `GET /api/v1/booths/{boothId}/projects/published` — 방문자 조회

방문자(게스트 포함)가 **공개된 부스**의 프로젝트 전시를 읽는다. FR-005·SC-002의 경로다.

편집자 조회(§3)와 **경로를 나눈 이유**: 같은 URL에서 신원에 따라 200과 403이 갈리면 게이트가
응답을 보고도 설명되지 않는다. `/published` 접미사는 이 저장소에서 이미 "게시 게이트 뒤의
방문자용 읽기"라는 뜻으로 두 번 쓰이고 있다 — `GET /booths/{boothId}/layouts/published`(005),
`GET /games/{gameId}/published`(019). 세 번째로 같은 뜻에 같은 이름을 쓴다.

### 인증 — 게스트 허용

- **토큰 없이도 `200`이다.** `SecurityConfiguration`에 `permitAll`로 등록한다
- 토큰이 있으면 `likedByMe` 판정에만 쓴다. 게스트·비회원 토큰은 `likedByMe: false`
- **편집자 경로(§3)는 이 변경에 영향받지 않는다** — 여는 것은 정확히 이 접미사 하나이고,
  `/api/v1/booths/*/projects`는 계속 인증 뒤에 있다

### 게이트 — 순서가 계약이다

`GET /booths/{boothId}`(004, `BoothQueryService.findPublicBooth`)의 순서를 그대로 따른다. 순서가
바뀌면 같은 부스가 상황에 따라 다른 오류를 내고, 방문자는 무엇이 잘못됐는지 알 수 없다.

| 순서 | 조건 | 응답 |
|---|---|---|
| 1 | 부스 없음 | `404 BOOTH_NOT_FOUND` |
| 2 | 유효 임대 없음 (만료) | `409 BOOTH_LEASE_EXPIRED` — 만료는 세계에 밀어내지 않으므로 방문자는 여기서 안다 (004 FR-019) |
| 3 | `booths.published_layout_version IS NULL` | `404 LAYOUT_NOT_PUBLISHED` |
| 4 | 통과. 프로젝트 없음 | `200 { "projects": [] }` |
| 5 | 통과. 프로젝트 있음 | `200 { "projects": [ … ] }` |

**게시 게이트는 `published_layout_version`이다.** 프로젝트에 별도의 게시 상태를 만들지 않는다 —
016 홈페이지가 같은 술어를 쓴다(`BoothQueryService.visibleHomepageUrl`). 프로젝트 패널은 게시된
배치 안의 오브젝트라서, 방문자가 그것을 누를 수 있는 순간과 이 술어가 정확히 겹친다. 읽을 수는
있는데 도달할 수 없는 창이 생기지 않는다.

**3과 4는 다른 사실이라 다른 응답이다** (#110, 2026-08-31 FE 질의).

| | 뜻 | 방문자 화면 |
|---|---|---|
| `404 LAYOUT_NOT_PUBLISHED` | 이 부스는 아직 방문자에게 열려 있지 않다 | 애초에 들어올 수 없는 부스다 |
| `200 { "projects": [] }` | 부스는 열렸고, 전시가 아직 없다 | 빈 상태 (-134 완료조건 "미등록 Booth 빈 상태 처리") |

둘을 빈 배열 하나로 뭉치면 FE가 이 둘을 구분할 수단이 없어진다. §3의 빈 배열은 계속
**"아직 안 만들었다"** 하나만 뜻한다.

### 응답 — `200`

```json
{
  "projects": [
    {
      "projectId": 1,
      "name": "SSAFY FESTA",
      "description": "메타버스 축제 플랫폼",
      "thumbnailUrl": "https://cdn.example.com/thumb.png",
      "videoUrl": "https://youtu.be/xxxx",
      "deployUrl": "https://festa.example.com",
      "gitUrl": "https://lab.ssafy.com/team/festa",
      "portfolioUrl": null,
      "likeCount": 12,
      "likedByMe": false
    }
  ]
}
```

- **§1의 Project 표현 + 2필드**다. 필드를 빼지 않는다 — 방문자에게 보이라고 만든 값들이다
  (FR-005, SC-002). 편집자 응답과 같은 이름·같은 타입이라 FE 파서를 하나로 쓴다
- **배열이다.** 부스당 1개(C-01)라도 형태를 유지한다
- `likeCount`: `project_likes` 행 수. **항상 존재하고 `null`이 아니다.** 없으면 `0`
- `likedByMe`: 이 요청자가 눌렀는지. **게스트·비로그인은 `false`**
- 나머지 필드의 `null` 규칙·URL 규칙은 §1과 같다. 저장 바이트 = 반환 바이트

> **`videoUrl`은 저장값 그대로 낸다.** C-02(제공자 목록) 미결 상태에서 서버가 임의로 걸러 숨기지
> 않는다 — §1의 소급 처리 방침과 같다. 임베드 판정은 FE 몫이고, 실패는 조용히 비우지 않고
> 드러낸다 (FR-009, T-24).

### 실패

| 상태 | code | 언제 |
|---|---|---|
| 404 | `BOOTH_NOT_FOUND` | 부스 없음 |
| 409 | `BOOTH_LEASE_EXPIRED` | 유효 임대 없음 |
| 404 | `LAYOUT_NOT_PUBLISHED` | 게시된 배치가 없다 = 방문자에게 열리지 않은 부스 |
| 401 | `UNAUTHORIZED` | **토큰을 실었는데 그 토큰이 만료·손상됐다** (아래) |

**`403`이 없다.** 게스트가 정상 경로다.

> ⚠️ **`401`은 토큰을 실은 경우에만 나온다.** 헤더가 아예 없으면 `200`이지만, `Authorization`을
> 실었고 그 토큰이 만료·손상됐으면 **`401 UNAUTHORIZED`다.** 인증 필터가 인가(`permitAll`)보다
> 먼저 돌아 거기서 응답을 끝내기 때문이고, `layouts/published`·`games/{id}/published`도 같다 —
> 이 endpoint만의 성질이 아니라 플랫폼 동작이다.
>
> **FE가 할 일**: 이 경로에서 `401`을 만나면 갱신 후 재시도하거나, 갱신이 안 되면 **헤더를 빼고
> 다시 부른다** — 게스트로서는 읽을 수 있다. `401`을 "전시가 없다"로 렌더하지 마라.

### 좋아요와의 경계 — 이 문서가 정하는 것은 읽기뿐

| | 티켓 | 이 문서 |
|---|---|---|
| `likeCount`·`likedByMe` **읽기** | -177 | §6 |
| 좋아요 **누르기·취소** (1인 1좋아요) | -135 | **§8** |

두 필드를 -177에서 미리 넣은 이유는 그 작업 내용이 *"Booth 기준 Project 조회 API (좋아요 수 포함)"*
이기 때문이다. `project_likes`는 V1부터 있고 쓰기 경로만 없어서 -135 전까지 `likeCount`는 항상
`0`, `likedByMe`는 항상 `false`였다. **-135가 붙어도 이 응답의 모양은 바뀌지 않았다** — 예고한
대로 값만 움직인다. §8이 쓰는 두 필드가 여기의 두 필드이고, 이름·타입이 같아 파서가 하나다.

### 재임대

새 임차인은 자기 Booth를 빈 상태로 받으므로(C-04 = 004 C-01), 이 endpoint가 이전 임차인의
프로젝트를 낼 경로가 없다. `projects.booth_id` 하나로 이미 갈린다 — 추가 조건이 필요 없다.

---

## 7. 확정 근거

| 항목 | 근거 |
|---|---|
| 부스당 1개 · 중복 409 · 목록 형태 유지 | C-01 (GitLab #110, 2026-08-28) |
| 영상 제공자 범위 | **C-02 미결** — 기획 대기 |
| 업로드 미지원·URL 참조·`thumbnailUrl` null 허용 | C-03 (2026-08-27) |
| 재임대 시 원 소유자 귀속 | C-04 = spec 004 C-01 적용 |
| 링크 3칼럼 (`project_links` 없음) | C-05 |
| `PATCH` 키 존재 여부 판정 | C-06 |
| `PROJECT_NOT_FOUND` 신설 | C-07 |
| URL은 형식 검증만 (allowlist 없음) | `spec 004 §D09` · `009 FR-004` · SC-002 |
| 만료 부스 쓰기 거부·읽기 허용 | facade·homepage 선례 + `009 FR-008` |
| 방문자 경로 분리 · `/published` 접미사 | 005 `layouts/published` · 019 `games/{id}/published` 선례 |
| 게시 게이트 = `published_layout_version` | 016 `visibleHomepageUrl` 선례 (research R-05) |
| 미게시는 404, 빈 배열이 아님 | GitLab #110 (2026-08-31) — FE 질의에 대한 답 |
| `likeCount`·`likedByMe` 읽기만 -177 | Jira `S15P21A604-177` 작업 내용 "좋아요 수 포함" · 쓰기는 -135 §8 |
| 좋아요를 토글 1개가 아니라 멱등 `PUT`·`DELETE` 로 | 재시도·더블탭이 좋아요를 뒤집지 않아야 한다 (§8) · 쿼리도 더 적다 |
| 1인 1좋아요를 앱이 아니라 PK 로 | `project_likes PRIMARY KEY(project_id, user_id)` (V1) — 앱 검사는 동시 요청 사이가 열린다 |
| 본인 프로젝트 좋아요 허용 | 금지 근거가 계약·spec·이슈에 없다 (§8) — 임의 확정하지 않는다 (헌법 30조) |

---

## 8. 좋아요 — `PUT`·`DELETE /api/v1/projects/{projectId}/like`

방문자가 전시에 반응한다 (FR-006, -135). §6이 읽던 두 필드를 이 절이 바꾼다.

```text
PUT    /api/v1/projects/{projectId}/like   → 200
DELETE /api/v1/projects/{projectId}/like   → 200
```

```json
{ "likeCount": 13, "likedByMe": true }
```

### 토글 하나가 아니라 멱등 둘이다 — 계약이다

`POST /like` 하나로 뒤집지 않는다.

| | 토글 1개 | `PUT`·`DELETE` |
|---|---|---|
| 같은 요청 두 번 | **두 번째가 방금 누른 것을 취소한다** | 결과 같음 |
| 더블탭·네트워크 재시도 | 사용자가 누른 적 없는 취소를 본다 | 안전 |
| 쿼리 | 존재 확인 + 분기 + 쓰기 | `INSERT … ON CONFLICT DO NOTHING` 하나 |

FE는 `likedByMe`를 보고 메서드를 고른다 — 그 값은 §6 응답에 이미 있다.

- **`PUT`은 이미 누른 회원이 다시 불러도 `200`이고 `likeCount`가 늘지 않는다.**
- **`DELETE`는 누른 적 없는 회원이 불러도 `200`이다** — `404`가 아니다. 결과가 같으므로 답도 같다.
- `likedByMe`는 `PUT` 뒤 항상 `true`, `DELETE` 뒤 항상 `false`다. 서버가 다시 조회하지 않는다 —
  성공 반환이 곧 행의 유무이기 때문이고, 그래서 이 값은 추측이 아니라 사후조건이다.

### 1인 1좋아요는 DB가 보장한다

`project_likes`의 `PRIMARY KEY(project_id, user_id)`(V1)가 그 규칙이다. 애플리케이션에 중복 검사를
따로 두지 않는다 — 두면 같은 회원의 두 요청이 겹칠 때 그 사이가 열린다.

### 응답에 프로젝트 전체를 담지 않는다

좋아요는 나머지 여덟 필드를 바꾸지 않는다. 같이 보내면 소비자가 "이 응답으로 화면을 다시 그려야
하나"를 매번 판단해야 한다. 두 필드는 §6과 **같은 이름·같은 타입**이라 파서를 하나로 쓴다.

### 게이트 — §6과 같은 함수다

```text
PROJECT_NOT_FOUND 404 → BOOTH_LEASE_EXPIRED 409 → LAYOUT_NOT_PUBLISHED 404
```

프로젝트를 먼저 찾는다. 없으면 어느 부스를 물어야 할지도 모르므로 이 순서에는 선택의 여지가 없다
(§4 `PATCH`와 같다). 그 뒤 부스 게이트는 §6이 쓰는 함수를 **그대로 호출한다** — 나누면 같은 부스가
무엇을 물었는지에 따라 다른 사유를 답할 수 있다.

**편집자 가드를 걸지 않는다.** 좋아요는 방문자의 행위이고 남의 부스에서 누르는 것이 정상 경로다.
`requireEditor`를 넣으면 기능이 자기 부스 전용이 된다.

**`DELETE`도 같은 게이트를 탄다 — 알고 닫는 문이다.** 임대가 만료되면 누른 사람도 취소할 수 없다
(`409`). 만료 동안은 §6 조회도 같이 막혀 아무도 그 좋아요를 보지 못하지만, **같은 소유자가 다시
임대하면 되살아난다** — `BoothLeaseService.ownBooth`가 `findByOwnerUserId`로 같은 booth 행을
재사용하므로 옛 프로젝트와 그 좋아요가 그대로 다시 보인다. 그 창 동안 누른 사람에게는 회수
수단이 없다.

`DELETE`만 게이트를 낮추는 안(프로젝트 존재만 확인)을 검토했고 **채택하지 않았다.** 게이트가
읽기·쓰기 한 함수라는 것이 §8의 단순함이고, 만료 중에는 보이지 않으므로 급하지 않다. 이 판단을
뒤집으려면 `unlike`에서 `requireLikeableProject` 대신 `findById` 한 줄이면 된다 —
**이 절을 먼저 고치고 바꾼다.**

### 인증 — 회원만

게스트는 `403 MEMBER_ONLY`다 (§1, 헌법 12조). §6과 다른 점이다 — 읽기는 게스트가 정상이지만
좋아요는 소유이므로 귀속될 계정이 있어야 한다.

### 본인 프로젝트에 누를 수 있다

막지 않는다. 계약·spec·이슈 어디에도 금지가 없고 `PRIMARY KEY`도 허용한다. 금지가 필요해지면
`requireLikeableProject`에 조건 한 줄이다 — 나중에도 싸므로 지금 임의로 확정하지 않는다.

### 실패

| 상태 | code | 언제 |
|---|---|---|
| 401 | `UNAUTHORIZED` | 토큰 없음·만료·손상 (§6과 달리 토큰이 필수다) |
| 403 | `MEMBER_ONLY` | 게스트 |
| 404 | `PROJECT_NOT_FOUND` | 그런 프로젝트가 없다 |
| 404 | `BOOTH_NOT_FOUND` | 프로젝트가 붙은 부스가 없다 |
| 404 | `LAYOUT_NOT_PUBLISHED` | 방문자에게 열리지 않은 부스다 |
| 409 | `BOOTH_LEASE_EXPIRED` | 유효 임대가 없다 |

**신규 오류 코드가 없다.** 여섯 개 전부 §5·§6·004가 이미 쓰는 것이다 — `docs/08` §18에 추가할
행이 없다.
