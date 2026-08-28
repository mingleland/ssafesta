# Data Model: 016 부스 홈페이지 URL (BE분)

**Phase 1** | 2026-08-24 | 근거: [research.md](research.md)

## 1. 스키마 — 신규 마이그레이션 **0개**

필요한 저장소는 전부 이미 있다. 이 기능은 스키마를 만들지 않고 **V1부터 잠들어 있던 컬럼에 처음으로 읽기·쓰기 경로를 붙이는 것**이다 (013a와 같은, 컬럼이 먼저 준비된 경우).

| 대상 | 상태 | 근거 |
|---|---|---|
| `booths.homepage_url VARCHAR(2048) NULL` | ✅ 존재 | `V1__initial_schema.sql:38` |
| JPA 매핑 `Booth.homepageUrl` | ✅ 존재 | `Booth.java:76-77` — `@Column(name = "homepage_url", length = 2048)` |
| DB 제약 | **두지 않는다** | 형식·스킴은 요청 검증(§3)으로만 건다. CHECK로 넣으면 규칙 변경이 마이그레이션이 된다 |

> 헌법 23조의 "저장 컬럼은 TEXT" 명령은 **아바타 인코딩**(길이 상한을 서버가 소유하지 않는 값)에 대한 것이다. URL은 상한 2048이 업계 표준이자 계약값("길이 제한" — BE.md §016)이라 `VARCHAR(2048)` 유지가 맞다 — T-24류(상한을 몰래 좁혀 조용히 거부)와는 반대로, 상한이 계약에 드러나 있고 위반은 사유 문장으로 거부된다.

## 2. 필드 의미

### `Booth.homepageUrl : String | null`

| 값 | 의미 |
|---|---|
| `null` | 미등록. 방문자 쪽은 안내 표시 대상이다(FR-009 — 오류 화면이 아니라 안내). **서버는 기본값·placeholder를 만들어 넣지 않는다** |
| 문자열 | 소유자가 등록한 절대 http/https URL. 서버는 스킴·형식 게이트만 통과시키고 내용(도달성·안전성)은 보증하지 않는다(R-04·R-09) |

**불변식 — 왕복 무손실**: `PUT`으로 저장한 바이트열과 `GET /booths/mine`·`GET /booths/{id}`(공개 시)가 돌려주는 바이트열은 **동일**하다. trim·정규화·대소문자 변경이 없다. (005 `layout_json`·013a `avatarCode` 원칙과 동일. 통합 테스트로 고정한다.)

## 3. 검증 규칙 (요청 게이트)

`BoothHomepageService` — 필드가 있고 `null`이면 통과(등록 해제, §5). 문자열이면 순서대로 검사하고 **첫 위반의 사유 문장**으로 400을 낸다.

| # | 규칙 | 위반 시 message |
|---|---|---|
| 0 | `homepageUrl` **키가 요청 본문에 존재** (필드 부재 `{}` 거부 — §5) | `homepageUrl 필드가 필요합니다.` |
| 1 | blank 금지 (해제는 명시적 `null`만 — §5) | `홈페이지 주소를 입력해 주세요.` |
| 2 | 길이 ≤ **2048** (V1 컬럼 폭 = facade `MAX_URL`과 동일) | `홈페이지 주소가 너무 깁니다. (최대 2048자)` |
| 3 | `java.net.URI` 파싱 성공 + **절대 URI** | `홈페이지 주소 형식이 올바르지 않습니다.` |
| 4 | scheme ∈ {`http`, `https`} (대소문자 무시 비교, 저장은 원문 그대로) | `홈페이지 주소는 http 또는 https로 시작해야 합니다.` |
| 5 | **host 존재** | `홈페이지 주소 형식이 올바르지 않습니다.` |

전부: `400 VALIDATION_FAILED` + `errors[0] = { rule: "FIELD_INVALID", field: "homepageUrl", message }` (R-07). `javascript:`·`data:`·`ftp:` 등은 #4가 차단한다(헌법 16조).

> **#4가 #5보다 앞인 이유**: `javascript:alert(1)`·`data:text/html,…`는 **host가 없다.** host를 먼저 보면 이들이 "형식 오류"로 끝나 실제 거부 사유(스킴 위반)가 사용자에게 전달되지 않는다. 차단 여부는 순서와 무관하게 같고, 달라지는 것은 **알려주는 이유**다.
>
> **#0을 두는 이유**: `record`처럼 필드 부재와 명시적 `null`을 같게 읽으면 FE 직렬화 실수(키 누락) 하나로 등록된 URL이 **조용히 삭제**된다. 해제는 의도를 담은 요청(`{"homepageUrl": null}`)만 인정한다 — §5의 "명시적 `null`만"이 요구하는 구현이다.

## 4. 노출 규칙 (응답 표면별)

FR-003("공개 상태일 때만 노출")의 서버측 구현 전부다. "공개 상태" = **공개된 Layout이 있는 상태**(`publishedLayoutVersion != null`) — 해석 근거는 R-05.

| 표면 | 대상 | `homepageUrl` 값 |
|---|---|---|
| `GET /booths/{boothId}` (PublicBoothView) | 방문자 | `publishedLayoutVersion != null`이면 저장값, 아니면 **null** |
| `GET /booths/mine` (MyBoothView) | 소유자 | 항상 저장값 (프리필 — R-06) |
| `PUT /booths/{boothId}/homepage` 응답 | 편집자 | 항상 저장값 echo |
| `GET /booths/{id}/layouts/published` (Layout 응답) | Unity | **포함하지 않는다** — Layout 계약 불변(R-01), Unity는 URL을 모른다(헌법 25조) |

만료 부스는 public 조회 자체가 `409 BOOTH_LEASE_EXPIRED`로 거부되므로(기존 FR-019 동작) 노출 규칙에 만료 분기가 따로 없다.

## 5. 상태 전이

`null ↔ 값`의 전체 교체뿐이다 — 등록(`null → 값`), 수정(`값 → 다른 값`), 해제(`값 → null`).

- 해제는 **명시적 `null`만** 인정한다. 빈 문자열 `""`도 400, **필드 자체가 없는 `{}`도 400**(§3 #0)이다 — "값이 없다"의 표현을 하나로 유지하고, 폼 초기화·직렬화 실수가 조용한 해제가 되는 것을 막는다.
- 013a와 달리 해제 경로를 만드는 이유: 잘못 등록한 주소를 내리는 수단이 없으면 FR-009의 "미등록 → 안내" 상태로 돌아갈 길이 없다(R-03). 아바타는 "항상 완전한 인코딩을 보내는" 소비자라 해제가 없었다.

## 6. 동시성

낙관적 잠금을 **걸지 않는다.** last-write-wins — facade와 동일한 판단이다. 편집 주체가 소유자+스태프 소수이고, 충돌로 잃는 것이 URL 문자열 1개라 revision 충돌 UI(005가 `version`을 둔 이유)를 만들 대상이 아니다.
