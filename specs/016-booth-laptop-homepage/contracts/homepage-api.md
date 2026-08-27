# 계약: 부스 홈페이지 URL API (spec 016 BE분)

> **상태: 확정 (2026-08-26)** — C-01(저장 위치)·C-02(개수) 모두 리드 확정 + FE 동의로 닫혔다([#97](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/97)). **이 계약을 근거로 각 파트가 착수한다.**
> 확정 결과 전체와 거기서 파생된 결정(FR-011 문구·Publish 경고 재해석·필드명 분리)은 §6에 있다.
>
> 근거: [spec.md](../spec.md) FR-001~FR-003·FR-009 · [BE/research.md](../BE/research.md) R-01~R-10 · [#97](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/97) 확정 회신

## 1. 이 계약이 정하는 것

| 항목 | 값 | 성격 |
|---|---|---|
| URL 저장 위치 | `booths.homepage_url` — **Layout JSON에 넣지 않는다** (Layout 계약 변경 0) | **C-01 확정** (#97) |
| 부스당 개수 | **1개** — LAPTOP 오브젝트가 여러 개여도 같은 URL | **C-02 확정** (#97) |
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
- **필드 자체가 없는 `{}`도 400이다** — 해제는 `homepageUrl`을 **명시적 `null`로 보낸 요청**만 인정한다. 필드 부재와 명시적 null을 같게 처리하면 FE 직렬화 실수(필드 누락) 하나로 등록된 URL이 조용히 지워진다. 서버는 요청 본문에 키가 있었는지를 구분해 읽는다.
- 검증(필드가 있고 문자열일 때, 순서대로 첫 위반으로 거부): blank 금지 → 길이 ≤ **2048** → URI 파싱·절대 URI → scheme ∈ {`http`, `https`} → host 존재. trim·정규화 없이 원문 그대로 저장한다.
  > **scheme을 host보다 먼저 본다** — `javascript:alert(1)`·`data:text/html,…`는 host가 없으므로 host를 먼저 검사하면 "형식이 올바르지 않다"로 끝나고, 실제 거부 사유인 **스킴 위반이 사용자에게 전달되지 않는다.** 차단 결과는 어느 순서든 같지만 알려주는 이유가 달라진다(T-24: 실패를 뭉개지 않는다).

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

> **`null`은 키를 생략하는 것이 아니다** — `homepageUrl` 키는 **항상 응답에 존재**하고 값이 `null`로 의미를 전달한다(`PUT` 해제 응답·`/mine`·공개 조회 모두). FE가 `null` 하나로 분기하는 계약이 성립하려면 키가 사라지지 않아야 한다. 서버 전역 JSON 설정에 null 제외(`default-property-inclusion=non_null`)를 걸면 이 계약이 깨진다.
> 검증도 이 구분을 표현해야 한다 — `jsonPath(…).doesNotExist()`는 **키 부재와 명시적 `null`을 똑같이 통과시켜** 계약을 지키지 못하고, `exists()`는 반대로 명시적 `null`에서 실패한다. 응답 본문을 파싱해 **키 존재와 `null`을 따로** 단언한다 (`BoothHomepageApiIntegrationTest.assertPresentAndNull`, T-97).

임대 만료 부스는 이 조회 자체가 `409 BOOTH_LEASE_EXPIRED`다 (기존 동작 — "만료 직후 노트북 클릭" Edge Case 커버).

**소유자 프리필**: `GET /api/v1/booths/mine` 응답(MyBoothView)에도 `homepageUrl`이 추가되며, 이쪽은 게이트 없이 **항상** 저장값이다 (미공개 상태의 스튜디오 폼 프리필용).

> **필드명 주의 (#97 리드 확정)** — 회차 필드는 endpoint마다 이름이 다르고 **합치지 않는다.**
> `GET /booths/{boothId}`(Booth 상세)는 **`publishedLayoutVersion`**, Layout Draft 조회·Publish 결과는 **`publishedVersion`** 이다.
> FE 계약 사본(`entities/layout/api.mock.ts:49`)이 Layout 쪽 이름을 쓰고 있어 혼동 가능성이 제기됐고, 리드가 위와 같이 분리 확정했다.

## 3-1. Publish 검증 — `LAPTOP` 경고의 근거가 `configId`에서 URL로 바뀐다

C-01 확정으로 `LAPTOP`은 **`configId`를 영원히 갖지 않는다.** 지금 Validator는 `requiresConfig=true`인 타입에 `configId`가 없으면 `CONFIG_NOT_LINKED` 경고를 내므로, 홈페이지를 제대로 등록한 부스에도 *"연결된 콘텐츠가 없습니다"* 가 **상시 오탐**으로 뜬다. 경고를 끄지 않고 **판정 근거만 URL로 바꾼다.**

| Layout 상태 | `booths.homepage_url` | Publish 응답 |
|---|---|---|
| `LAPTOP` 있음 | 있음 | 경고 없음 |
| `LAPTOP` 있음 | `null` | **warning `CONFIG_NOT_LINKED`** — *"홈페이지 주소가 등록되지 않았습니다."* |
| `LAPTOP` 없음 | `null` | 경고 없음 |

- **경고 코드와 봉투는 기존 `CONFIG_NOT_LINKED` 그대로다.** 조건과 문구만 바뀐다 — 신규 rule 0.
- 노트북이 없으면 경고하지 않는다. 경고의 목적이 *"방문자가 클릭했는데 아무것도 안 뜨는 상황"* 을 막는 것이라, 클릭할 대상이 없으면 경고할 일이 없다.
- `false` 전환(경고 자체를 끄는 안)은 **채택하지 않았다** — 그러면 "URL 미등록 노트북" 경고가 통째로 사라진다(FE 지적, #97). 재해석이 경고를 유지하면서 오탐만 없앤다.
- `LayoutValidator.java:203-205`(develop 기준) 주석이 *"공개를 막을지는 C-04, 정해지면 이 호출이 `addError`가 된다"* 로 남아 있다. C-04가 그 방향으로 가면 **노트북이 있는 모든 부스가 공개 불가**가 되므로 지금 정리해 둔다.
- ⚠️ **`requiresConfig` 플래그는 건드리지 않는다.** 호출처가 `LayoutValidator` 한 곳이 아니라 `LayoutPassageChecker.java:80`(시야 확보 검사)에도 있어, 끄면 LAPTOP이 그 검사에서 빠진다. 근거는 [research.md](../BE/research.md) R-10.

**FE 스튜디오에 필요한 것 2건** (#97 통보분):

1. **`LAPTOP` 오브젝트에 `configId`를 보내지 않는다.** 실어 보내면 `isVerifiable(LAPTOP)`이 `false`라 `CONFIG_UNVERIFIED` 경고가 붙는다. LAPTOP은 연결할 콘텐츠 ID가 없다.
2. **`entities/layout/objectTypes.ts:36`의 `warnOnMissingConfig`를 `false`로 내린다.** 서버가 `configId` 기준 경고를 더 이상 내지 않으므로, 두면 **FE만 사전에 없는 경고를 띄운다.** 같은 줄 주석(*"016에서 URL 계약으로 요건이 바뀔 수 있음"*)이 가리키던 자리다.

## 4. 소비 흐름 (Unity 변경 0 · FE는 조회 경로 신설 필요)

아래는 **확정 후의 목표 흐름**이다. 현재 FE 코드는 이렇게 동작하지 않는다 — 바로 아래 정정을 보라.

```text
Unity 노트북 클릭
  → BOOTH_LAPTOP_INTERACT { boothId, objectId, url? }     # 검증 완료된 기존 브리지 계약, 변경 없음
  → FE: GET /api/v1/booths/{boothId}
  → homepageUrl != null → 오버레이(iframe) 표시, 차단 감지 시 새 탭 (FR-007 — FE 몫)
  → homepageUrl == null → "아직 준비되지 않았다" 안내 (FR-009 — FE 몫)
```

> **정정 (#97, FE 지적 수용)** — 이 문서의 초안은 위 흐름을 *"FE 계약은 기존 그대로"* 라고 현재형으로 적었으나 **사실이 아니었다.** 지금 FE는 브리지 payload의 `url`만 읽고 **서버 조회 경로가 없다**(`LaptopOverlay.tsx:36` `resolveUrl(payload.url)`, 같은 파일에 API import 0건, `homepageUrl` grep 0건, `BoothDetail`은 `boothId`·`name`·`leaseStatus`·`facade` 4필드).
> C-01 확정으로 FE에 발생하는 작업은 ① `BoothDetail`에 `homepageUrl` 추가 ② `LaptopOverlay`를 `boothId` 기반 조회로 전환 ③ `dispatcher.test.ts:26-39` 갱신 — 3건이다. **BE 착수를 막지 않는다**(계약만 서면 양쪽 병행).

- 브리지의 `url?` 필드는 계속 **선택·비사용**이다 — Layout에 URL이 없으므로 Unity는 URL을 모른다(헌법 25조). 브리지 계약 변경 없음. FE가 `boothId`만 쓰게 되므로 **브리지에서 `url`을 빼는 데 FE는 동의**했다(#97) — 제거 여부는 Unity 파트 최종 확인 사항이며 이 계약의 전제는 아니다.
- 서버는 URL의 도달성·iframe 삽입 가능 여부를 판정하지 않는다 — 사전 판정은 기술적으로 불가능하고(spec §기술 리스크 2), 시도·감지·fallback은 React 레이어다(FR-005·FR-007).

## 5. 비범위

- 악성 링크 신고·차단·관리자 강제 비공개 — ADMIN 권한 모델(U-01)에 종속, 따로 설계하지 않는다 (2026-08-24 게임스튜디오 계약 검토 ⑦, R-09)
- 부스당 여러 URL — C-02가 **1개로 확정**됐다(#97). 나중에 뒤집히면 `booth_homepages` 테이블 분리로 확장한다(가산적 마이그레이션)
- 방문 통계 — spec 015 (Dashboard)
- URL 콘텐츠 안전성 보증 — 스킴 화이트리스트까지가 서버 몫

## 6. 확정 결과 (#97 — 2026-08-26 종료)

초안이 물었던 7건은 전부 답이 나왔다. 아래가 그 답이고, 이 계약은 이 결과를 반영한 상태다.

| # | 항목 | 확정 | 정한 사람 |
|---|---|---|---|
| 1 | **C-01** — URL 저장 위치 | ✅ `booths.homepage_url`, **Layout 불변** | 리드 확정 + FE 동의 |
| 2 | **C-02** — 부스당 개수 | ✅ **1개** | 리드 확정 + FE 동의 |
| 3 | FR-003 "공개 상태"의 해석 | ✅ `publishedLayoutVersion != null` (공개 Layout 존재) | FE 동의, 이견 없음 |
| 4 | http 허용 유지 | ✅ **허용 유지** (FR-002 그대로, 혼합콘텐츠 경고 UX는 FE) | FE 동의 |
| 5 | `LAPTOP`의 `requiresConfig` | ✅ **재해석** — `false` 전환이 아니라 경고 근거를 URL 미등록으로 교체 (§3-1) | FE 선호 + BE 설계 |
| 6 | FR-011과 C-01의 양립 | ✅ **⑵ 채택** — C-01대로 가고 FR-011 문구를 고친다. 리드 확정 문구는 아래 | 리드 확정 |
| 7 | `docs/26` "LAPTOP 홈페이지 주소 저장 위치" 행 | ✅ **FE(행 작성자)가 정정** — *"컬럼 신규 추가"* → 기존 컬럼 사용 | FE 자인·담당 |

**#6 리드 확정 FR-011 문구** (spec.md 반영 대상 — 리드 문서라 BE가 직접 고치지 않는다):

> `LAPTOP`은 spec 005의 canonical Layout type에 이미 포함된다. 홈페이지 URL은 Layout JSON 또는 `configId`에 저장하지 않고 `booths.homepage_url`에 부스당 1개 저장한다. 따라서 홈페이지 URL 때문에 Layout schema를 확장하지 않는다.

**#7과 함께 정리 대상인 stale 행 1건** — 같은 `docs/26`의 *"미검증 잔여 1건 — `BOOTH_LAPTOP_INTERACT` 브라우저 왕복(WebGL 빌드 후)"* 도 stale이다. 실제 검증은 끝났고(`docs/sdd/parts/FE.md:52-54`, 5종 왕복 일치) **날짜는 2026-08-20**이다 — `462c4a2`는 그 기록을 남긴 커밋이라 커밋일이 08-21인 것이고, 이 문서 초안의 R-01이 인용한 "2026-08-21 검증"은 둘을 섞은 것이다. FE가 함께 정정하기로 했다.

### 미해결로 남은 것 1건 (BE 착수를 막지 않음)

`spec.md:120` 기술 리스크 §3의 *"배포 후에는 `http` 주소가 아예 동작하지 않는다"* 가 FR-002(http 허용)와 **문면상 충돌**한다는 FE 지적이 있었고, *"FR-002는 형식 허용 / 실제 로드 성공 여부는 별개"* 로 분리해 달라는 요청이 리드에게 갔으나 회신이 없다. spec 본문 문구 문제라 이 계약의 동작에는 영향이 없다 — 서버는 §2대로 http를 통과시킨다.
