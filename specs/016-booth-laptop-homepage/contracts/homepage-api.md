# 계약: 부스 홈페이지 URL API (spec 016 BE분)

> **상태: BE 제안 (2026-08-24)** — C-01(저장 위치)·C-02(개수)는 docs/26 미결 항목이며 이 문서는 FE 의견·기존 ERD와 같은 방향의 제안이다.
> 3파트 확정 + FE 통보 후 이 줄을 갱신한다. 확정 전 이 계약을 근거로 다른 파트가 착수하지 않는다.
>
> 근거: [spec.md](../spec.md) FR-001~FR-003·FR-009 · [BE/research.md](../BE/research.md) R-01~R-09 · `docs/26` "LAPTOP 홈페이지 주소 저장 위치" 행

## 1. 이 계약이 정하는 것

| 항목 | 값 | 성격 |
|---|---|---|
| URL 저장 위치 | `booths.homepage_url` — **Layout JSON에 넣지 않는다** (Layout 계약 변경 0) | **C-01 제안** |
| 부스당 개수 | **1개** — LAPTOP 오브젝트가 여러 개여도 같은 URL | **C-02 제안** |
| 등록·수정·해제 | `PUT /api/v1/booths/{boothId}/homepage` (신설) | 가산적 |
| 방문자 노출 | `GET /api/v1/booths/{boothId}` 응답 필드 추가 + published 게이트 | 가산적 |
| 소유자 프리필 | `GET /api/v1/booths/mine` 응답 필드 추가 | 가산적 |

전부 **가산적**(endpoint 신설 + 응답 필드 추가)이라 Breaking Change가 아니다. 기존 소비자는 수정 없이 동작한다.

## 2. `PUT /api/v1/booths/{boothId}/homepage` — 등록·수정·해제

```http
PUT /api/v1/booths/7/homepage
Authorization: Bearer <member access token>
Content-Type: application/json

{ "homepageUrl": "https://my-team-project.example.com" }

→ 200 { "homepageUrl": "https://my-team-project.example.com" }   # 저장한 그대로 echo
```

- 소유자와 등록된 스태프가 호출할 수 있다 (facade·layout과 동일한 `BoothEditorGuard`).
- Draft/Publish를 타지 않는다 — 저장 즉시 `booths` 컬럼에 반영 (facade와 동일). 방문자 **노출**은 §3 게이트가 따로 건다.
- `{ "homepageUrl": null }` = **등록 해제** → 방문자에게 미등록(안내) 상태로 돌아간다. 빈 문자열 `""`은 해제가 아니라 400이다.
- 검증(문자열일 때, 순서대로 첫 위반으로 거부): blank 금지 → 길이 ≤ **2048** → URI 파싱·host 존재 → scheme ∈ {`http`, `https`}. trim·정규화 없이 원문 그대로 저장한다.

| 실패 | 응답 |
|---|---|
| 검증 위반 | `400 VALIDATION_FAILED` + `errors[0] = { rule: "FIELD_INVALID", field: "homepageUrl", message: <사유별 다른 문장> }` (#58 5필드 봉투) |
| 미인증 | `401` |
| 소유자·스태프 아님 | `403 BOOTH_EDITOR_FORBIDDEN` |
| 부스 없음 | `404 BOOTH_NOT_FOUND` |
| 임대 만료 | `409 BOOTH_LEASE_EXPIRED` |

신규 오류 코드·rule 없음.

## 3. 방문자 노출 — `GET /api/v1/booths/{boothId}` 확장

응답(PublicBoothView)에 `homepageUrl` 필드가 추가된다. **인증 불필요** (기존과 동일).

```json
{
  "boothId": 7,
  "slotId": 5,
  "name": "AI 프로젝트 전시관",
  "leaseStatus": "ACTIVE",
  "entryAvailable": true,
  "endsAt": "2026-09-01T00:00:00Z",
  "facade": { "themeCode": "SSAFY_BLUE", "primaryColor": "#3B82F6", "signText": "…", "logoUrl": null },
  "publishedLayoutVersion": 4,
  "homepageUrl": "https://my-team-project.example.com"
}
```

**노출 게이트 (FR-003)**: `publishedLayoutVersion`이 `null`이면 `homepageUrl`도 **`null`** 로 내려간다 — "공개 상태" = **공개된 Layout이 있는 상태**로 해석한다(노트북은 공개 Layout 안에만 존재하므로 방문자가 URL을 쓰는 순간과 일치). 미등록이어도 `null`이다 — FE는 `null` 하나로 "미등록/미공개" 안내 분기를 끝낸다(FR-009).

임대 만료 부스는 이 조회 자체가 `409 BOOTH_LEASE_EXPIRED`다 (기존 동작 — "만료 직후 노트북 클릭" Edge Case 커버).

**소유자 프리필**: `GET /api/v1/booths/mine` 응답(MyBoothView)에도 `homepageUrl`이 추가되며, 이쪽은 게이트 없이 **항상** 저장값이다 (미공개 상태의 스튜디오 폼 프리필용).

## 4. 소비 흐름 (참고 — FE·Unity 계약은 기존 그대로)

```text
Unity 노트북 클릭
  → BOOTH_LAPTOP_INTERACT { boothId, objectId, url? }     # 검증 완료된 기존 브리지 계약, 변경 없음
  → FE: GET /api/v1/booths/{boothId}
  → homepageUrl != null → 오버레이(iframe) 표시, 차단 감지 시 새 탭 (FR-007 — FE 몫)
  → homepageUrl == null → "아직 준비되지 않았다" 안내 (FR-009 — FE 몫)
```

- 브리지의 `url?` 필드는 계속 **선택·비사용**이다 — Layout에 URL이 없으므로 Unity는 URL을 모른다(헌법 25조). 브리지 계약 변경 없음.
- 서버는 URL의 도달성·iframe 삽입 가능 여부를 판정하지 않는다 — 사전 판정은 기술적으로 불가능하고(spec §기술 리스크 2), 시도·감지·fallback은 React 레이어다(FR-005·FR-007).

## 5. 비범위

- 악성 링크 신고·차단·관리자 강제 비공개 — ADMIN 권한 모델(U-01)에 종속, 따로 설계하지 않는다 (2026-08-24 게임스튜디오 계약 검토 ⑦, R-09)
- 부스당 여러 URL — C-02가 "여러 개"로 확정되면 별도 확장
- 방문 통계 — spec 015 (Dashboard)
- URL 콘텐츠 안전성 보증 — 스킴 화이트리스트까지가 서버 몫

## 6. 확정·확인 요청 항목 (통보 시 함께 묻는다)

| # | 질문 | BE 제안값 |
|---|---|---|
| 1 | **C-01** — URL 저장 위치 | `booths` 컬럼, Layout 불변 (FE 의견·V1 ERD와 동일 방향) |
| 2 | **C-02** — 부스당 개수 | 1개 (여러 개 확정 시 테이블 분리로 확장) |
| 3 | FR-003 "공개 상태"의 해석 | `publishedLayoutVersion != null` (공개 Layout 존재) |
| 4 | http 허용 유지 여부 | 허용 (FR-002 그대로 — 혼합콘텐츠 경고 UX는 FE) |
| 5 | `LAPTOP`의 `requiresConfig` | 부스 단위 URL 확정 시 configId 연결 대상이 없어 `CONFIG_NOT_LINKED` 경고가 상시 오탐 — `false` 전환(BE 1줄) 제안, FE 스튜디오 경고 UX와 함께 결정 |
| 6 | **FR-011과 C-01이 양립하는지** — spec 본문은 **손대지 않았다** | FR-011은 *"`LAPTOP` 오브젝트는 Layout 계약(spec 005)에 추가되어야 하며, 주소는 정수 `configId`로 표현할 수 없으므로 **계약 확장이 필요하다**"* 로 규정한다. C-01(`booths` 컬럼, Layout 불변)로 가면 **확장할 것이 없어진다.** 전반부는 이미 충족돼 있다(`LAPTOP`은 canonical 10종에 존재, `LayoutObjectType.java:29`). **`spec.md`는 리드 문서라 제가 고치지 않았다** — ⑴ FR-011을 그대로 두고 C-01을 다시 볼지 ⑵ C-01대로 가고 FR-011 문구를 고칠지, 어느 쪽인지 정해 주십시오. ⑵면 문구도 정해 주시면 그대로 따르겠습니다 |
| 7 | **`docs/26` "LAPTOP 홈페이지 주소 저장 위치" 행** — 실측이 그 행의 전제와 다르다 | 그 행의 FE 의견이 *"`booths`에 URL 컬럼 **신규 추가**"* 인데, 컬럼은 **V1부터 있습니다**(`V1__initial_schema.sql:38` `homepage_url VARCHAR(2048)`, `Booth.java:76-77` JPA 매핑). 읽기·쓰기 경로만 없습니다. **`docs/26`은 팀 정본이라 제가 고치지 않았습니다** — 그대로 둘지, "컬럼·매핑은 이미 존재(V1)"를 덧붙일지, 누가 고칠지 알려 주십시오. 제가 굳이 올리는 이유는 그 문구가 남으면 다음 사람이 불필요한 마이그레이션을 만들 수 있다는 것뿐입니다 (#59의 행 소유자 얘기와 같은 건) |
