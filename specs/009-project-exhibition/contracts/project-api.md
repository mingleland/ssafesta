# Project Exhibition API 계약 v1

**Spec**: [../spec.md](../spec.md) | **Date**: 2026-08-28 | **Jira**: S15P21A604-110 (쓰기·편집자 조회) · S15P21A604-177 (방문자 조회)

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
| 방문자 조회 (published 게이트 + 좋아요 수) | **-177** | 범위 밖 |
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
- 방문자용 조회는 이 endpoint가 아니다 → **S15P21A604-177**

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

## 6. 확정 근거

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
