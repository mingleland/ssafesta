# Research: 016 부스 홈페이지 URL (BE분)

**Phase 0** | 2026-08-24 | 입력: [spec.md](../spec.md) · 헌법 v1.2 · `docs/sdd/parts/BE.md` §016 · `docs/26_팀_결정_필요사항.md` "LAPTOP 홈페이지 주소 저장 위치" 행 · `docs/sdd/parts/FE.md` §016(브리지 왕복 검증 기록) · `docs/HDD/게임스튜디오_계약_검토.md` ⑦

모든 결정은 **실측**(코드·계약 문서·팀 결정 기록)에서 나왔다. 추측으로 확정한 값은 없다.
단, R-01·R-02는 팀 미결 항목(C-01·C-02)에 대한 **BE 제안**이다 — 확정 절차를 [quickstart.md](quickstart.md) §4에 고정했다. 문서는 `제안` 표기로 머지하고 **구현(코드) 착수만 확정 후**다(하단 "팀 확정 게이트" · "머지 정책 정정").

---

## R-01. 저장 위치 = `booths.homepage_url` — Layout 계약은 건드리지 않는다 (C-01 제안)

- **Decision**: URL은 `booths.homepage_url`(V1부터 존재)에 부스당 1개로 저장한다. Layout JSON에는 넣지 않는다 — Layout 계약(005) 변경 **0**.
- **Rationale**: 네 갈래 근거가 전부 같은 방향이다. ① FE 공식 의견이 이것이다 — docs/26 미결 행: *"부스 설정에 저장하고 Layout에는 넣지 않는다(`booths`에 URL 컬럼 신규 추가, 부스당 1개). 이러면 Layout 계약 변경이 없다."* ② ERD가 이미 이 방향으로 시공됐다 — `V1__initial_schema.sql:38`의 `homepage_url VARCHAR(2048)`, `Booth.java:76-77` JPA 매핑. 컬럼·매핑은 있고 **쓰기·읽기 경로만 없다**(이 기능이 그 경로다). ③ 검증 완료된 브리지 계약과 정합한다 — `BOOTH_LAPTOP_INTERACT { boothId, objectId, url? }`(**2026-08-21 리드가 WebGL 빌드에서 실제 클릭 왕복 검증**, `docs/sdd/parts/FE.md:54` · 커밋 `462c4a2`. 08-20은 `assetCode` 종단 검증 날짜라 섞지 않는다. ⚠️ `docs/26` Booth Layout 스키마 행은 아직 *"미검증 잔여 1건 — BOOTH_LAPTOP_INTERACT 브라우저 왕복"* 로 남아 있다 — 08-20 `aeccede` 기록이 08-21 검증을 못 따라간 stale이며, 계약 초안 §6-7에 함께 올렸다)에서 `url`이 **선택**인 이유가 이것이다: Layout에 URL이 없으므로 Unity는 `url`을 채울 수 없고, FE가 `boothId`로 서버에서 읽는다. ④ 반대안(Layout 포함)은 spec FR-011이 지적한 대로 정수 `configId` 계약의 확장 = 3파트 Breaking 재합의가 필요하다. `LAPTOP` 타입 자체는 이미 canonical 10종에 들어 있어(`LayoutObjectType`, layout-api.md §type) 이 방향에서는 Unity·FE 계약 변화가 전혀 없다.
- **Alternatives**: ① Layout JSON에 URL 포함 — 위 ④로 기각. ② `configId`로 별도 homepage 테이블 참조 — 부스당 1개(R-02)면 간접층만 늘고, "configId = 그 부스 소유 콘텐츠 ID" 검증 계약에 새 콘텐츠 종류를 추가하는 재합의가 필요하다.
- **절차 (헌법 30조)**: C-01은 docs/26에 **3파트 합동 미결**로 등록돼 있다. 구현자가 임의 확정하지 않는다 — 본 산출물은 FE 의견·기존 스키마와 같은 방향의 제안이고, 확정 요청(추적 이슈 + docs/26 기입)은 초안을 만들어 **사용자 승인 후 게시**한다.

## R-02. 개수 = 부스당 **1개** (C-02 제안)

- **Decision**: 홈페이지 URL은 부스당 1개다. `LAPTOP` 오브젝트를 여러 개 배치해도 모두 같은 URL을 연다.
- **Rationale**: `BE.md` §016("홈페이지 URL 1개를 등록한다") + FE 의견("부스당 1개") + 단일 컬럼 스키마가 일치한다. C-02(기획)가 나중에 "여러 개"로 확정되면 `booth_homepages` 테이블 분리로 확장한다 — 컬럼→테이블 이관은 가산적 마이그레이션이라 지금 1개로 가는 것이 잠기는 결정이 아니다.
- **Alternatives**: 오브젝트(`objectId`)별 URL — 브리지 payload에 `objectId`가 이미 있어 기술적으로는 열려 있으나, 기획 수요가 미확인인 상태에서 테이블·계약을 선제로 넓히는 것은 30조 위반 방향이다.
- **절차**: docs/26 행이 *"FR-001의 '여러 개 허용 여부'(C-02)가 미결이라 함께 정해야 함"*이라 명시한다 — C-01 확정 요청에 C-02를 **함께** 묻는다.

## R-03. 쓰기 경로 = `PUT /api/v1/booths/{boothId}/homepage` — facade 선례의 반복

- **Decision**: `PUT /api/v1/booths/{boothId}/homepage`, body `{ "homepageUrl": "https://…" | null }`, 응답은 저장값 echo. Draft/Publish 파이프라인에 태우지 않고 `booths` 컬럼 직접·즉시 반영. 권한은 `BoothEditorGuard.requireEditor`(소유자+스태프), 유효 임대 필수(만료 시 `409 BOOTH_LEASE_EXPIRED`). `null`은 등록 해제다.
- **Rationale**: 같은 성격의 부스 단위 설정인 facade가 정확히 이 모양으로 확정·통보됐다(docs/26: *"⑴ `PUT /booths/{id}/facade` ⑵ Draft/Publish 없이 `booths` 컬럼 직접·즉시 반영 … 만료 부스는 편집 거부(`BOOTH_LEASE_EXPIRED`)"*, 2026-08-20 #17). 편집 권한·만료 규칙이 layout·facade와 다르면 스튜디오 UX가 갈라진다. 해제(`null`)를 두는 이유: 잘못 등록한 주소를 내리는 수단이 없으면 FR-009의 "미등록 → 안내" 상태로 돌아갈 길이 없다(facade `logoUrl: null` 허용과 동일).
- **"저장 즉시 반영"과 FR-003의 관계**: 즉시 반영되는 것은 **저장값**이고, 방문자 **노출**은 R-05 게이트가 따로 건다. spec US2의 "저장 → 공개 → 반영"은 공개 전 노출이 없다는 뜻이고, 이미 공개 중인 부스의 URL 교체가 즉시 보이는 것은 FR-003 위반이 아니다(facade 즉시 반영과 같은 판단).
- **Alternatives**: ① `FacadeCommand`에 필드 추가 — facade는 "정해진 4필드"로 문서·통보가 끝난 계약이라(docs/08 §3) 오염이다. ② Layout draft에 포함 — R-01 기각 방향. ③ `PATCH` — 단일 필드 전체 교체라 PUT이 의미에 맞다(013a R-01과 동일 논거).

## R-04. 검증 = 스킴 화이트리스트(http/https) + 절대 URL + 길이 ≤ 2048

- **Decision**: `null`은 검증 없이 통과(해제). 그 외에는 순서대로 검사하고 **첫 위반의 사유 문장**으로 400을 낸다 — ① blank 거부 ② 길이 ≤ **2048** ③ `java.net.URI` 파싱 성공 + 절대 URI + host 존재 ④ scheme ∈ {http, https}(대소문자 무시 비교). trim·정규화 없이 **원문 그대로 저장**한다.
- **Rationale**: FR-002·`BE.md` §016("http/https 형식만 허용, 길이 제한")이 위임한 검증이 정확히 이 둘이다. 2048은 V1 컬럼 폭이자 facade `logoUrl` 상한(`MAX_URL`)과 같은 값. 스킴 화이트리스트는 `javascript:`·`data:` 주입을 자동 차단한다(헌법 16조). 정규화 금지는 왕복 무손실 원칙(005 `layout_json`·013a R-05와 동일) — 저장한 바이트열과 돌려주는 바이트열이 같음을 테스트로 고정한다.
- **http를 허용하는 이유 (facade와 의도적 비대칭)**: facade `logoUrl`은 https 전용이다 — 로고는 우리 페이지 안에 **리소스로 임베드**되어 mixed content로 조용히 죽기 때문(`BoothFacadeService.validateLogoUrl` 주석). 홈페이지 URL은 임베드가 아니라 **이동 대상**이고, iframe이 막혀도 새 탭 열기가 1급 기능(US3, SC-003 "새 창 포함 성공률 100%")이라 http여도 도달할 수 있다. spec Edge Case가 http 차단을 브라우저 몫으로 명시했고, FR-002가 http를 허용 목록에 넣었다. 혼합콘텐츠 경고 UX는 FE 예상 clarify에 이미 등록돼 있다(FE.md §016).
- **Alternatives**: ① https 전용 — FR-002 위반(계약이 http를 허용한다). ② 등록 시점 도달성(DNS/HEAD) 검증 — 네트워크 의존 실패·지연의 원인이고 spec이 요구하지 않는다(응답하지 않는 사이트 처리는 US3의 런타임 몫). ③ 정규식 검증 — URI 파서보다 구멍이 많다.

## R-05. 방문자 노출 = `PublicBoothView.homepageUrl` + **published 게이트**

- **Decision**: `GET /api/v1/booths/{boothId}` 응답에 `homepageUrl`을 추가하되, `publishedLayoutVersion == null`이면 **null**로 내린다. 별도 조회 엔드포인트는 만들지 않는다.
- **Rationale**: FR-003("공개 상태일 때만 노출")의 "공개"를 **공개된 Layout이 있는 상태**로 해석한다 — 노트북(`LAPTOP`)은 공개 Layout 안에만 존재하므로, 방문자가 URL이 필요한 유일한 순간과 게이트가 정확히 일치한다. 소비자 경로 실측: FE는 `BOOTH_LAPTOP_INTERACT{boothId}`를 받은 뒤 URL이 필요하고(FE.md §016), `GET /booths/{id}`는 이미 인증 없이 열려 있다(`SecurityConfiguration` — `GET /api/v1/booths/*` permitAll). 만료 부스는 이 조회 자체가 `409 BOOTH_LEASE_EXPIRED`로 거부되므로(spec 004 FR-019) Edge Case "임대 만료 직후 노트북 클릭"이 추가 코드 없이 처리된다. 미등록이면 null → FE가 안내를 표시한다(FR-009, 브리지 계약의 *"URL이 없으면 오류가 아닌 안내"*와 동일 원칙).
- **Alternatives**: ① 항상 노출 — FR-003 위반. ② 별도 `GET /booths/{id}/homepage` — 소비자가 없다. FE는 부스 조회를 이미 하고, null 하나로 "미등록/미공개" 분기가 끝난다(013a R-02와 동일 논거). ③ Published Layout 응답에 포함 — Layout 계약 변경(R-01 기각 방향)이고, Unity는 URL이 필요 없다(헌법 25조 — 표시는 웹 레이어).
- **절차**: "공개 상태 = published layout 존재"라는 해석은 [계약 초안](../contracts/homepage-api.md) §6에 확인 항목으로 명시해 FE 답을 받는다.

## R-06. 소유자 조회 = `MyBoothView.homepageUrl` (게이트 없음)

- **Decision**: `GET /api/v1/booths/mine` 응답에 `homepageUrl`을 추가한다 — 공개 여부와 무관하게 항상.
- **Rationale**: 스튜디오 등록 폼의 프리필 경로가 필요한데, 미공개 상태의 소유자는 R-05 게이트 때문에 public view로 자기 값을 읽을 수 없다. `/mine`은 이미 "내 부스" 전용 표면이라 노출 규칙과 충돌하지 않는다.
- **Alternatives**: ① PUT echo만으로 충분 — 페이지 재진입 시 빈 폼이 된다. ② public view에서 소유자에게만 예외 노출 — 호출자에 따라 응답이 달라지는 공개 계약은 캐시·문서화 양쪽에서 나쁘다.
- **참고**: 스태프는 `/mine`에 잡히지 않아 프리필이 안 되지만, facade도 동일한 상태라 여기서 따로 풀지 않는다 — FE 요구가 생기면 그때 다룬다.

## R-07. 실패 응답 = 기존 어휘 재사용 — 신규 오류 코드 **0**

- **Decision**: 검증 실패는 `400 VALIDATION_FAILED`, `errors[0] = { rule: "FIELD_INVALID", field: "homepageUrl", message: <사유 문장> }`. 부스 없음 `404 BOOTH_NOT_FOUND`, 편집 권한 없음 `403 BOOTH_EDITOR_FORBIDDEN`, 임대 만료 `409 BOOTH_LEASE_EXPIRED`, 미인증 401(PUT 경로는 `anyRequest().authenticated()`에 걸린다 — SecurityConfiguration 변경 없음).
- **Rationale**: #58 확정(전 endpoint 5필드 봉투) + `docs/08` §1.3-1 전역 rule(`FIELD_INVALID`는 문제 필드를 `field`에 담는다 — T058 구현 완료)을 그대로 탄다. facade 검증이 이미 같은 조합으로 나간다. 길이 초과·형식 오류·스킴 위반은 **다른 문장**으로 구분한다(T-24 교훈: 실패를 조용히 뭉개지 않는다).
- **Alternatives**: `HOMEPAGE_URL_INVALID` 전용 rule — 요청 필드 하나의 제약 위반이라 `FIELD_INVALID` 정의에 정확히 들어맞는다. 어휘를 늘릴 근거가 없다.

## R-08. 구현 위치 = facade 대칭의 소형 controller + service

- **Decision**: `BoothHomepageController`(PUT 1개) + `BoothHomepageService`(가드·임대 검증·URL 검증·변경) 신설, `Booth.changeHomepageUrl()` 도메인 메서드 추가, `BoothQueryService`의 두 record(`PublicBoothView`·`MyBoothView`)에 필드 추가.
- **Rationale**: `BoothFacadeController`/`BoothFacadeService`가 정확히 이 크기·이 모양이다(가드 → 유효 임대 → 검증 → 도메인 메서드 → view 반환). read side(`BoothController`)와 write를 섞지 않는 기존 구획을 유지한다.
- **Alternatives**: `BoothFacadeService`에 편입 — facade는 "정해진 4필드" 계약으로 문서화가 끝나 있어 코드와 계약 문서가 어긋나게 된다.

## R-09. 악성 링크 대응(C-05) = 이번 범위 **제외**

- **Decision**: 신고·차단·관리자 강제 비공개를 만들지 않는다.
- **Rationale**: 2026-08-24 게임스튜디오 계약 검토 ⑦이 이미 정리했다 — 관리자 강제 비공개는 ADMIN 권한 모델(docs/26 ①-16 U-01)에 막혀 있고, 004 C-05 신고·강제 비공개도 같은 벽으로 기능을 빼는 방향이며, *"016(노트북 홈페이지)의 악성 사이트 대응도 같은 결정에 종속이다. 셋을 따로 설계하지 말 것."* 스킴 화이트리스트(R-04)가 기술적 주입(`javascript:` 등)은 막고, 콘텐츠 수준 대응은 U-01 확정 후 일괄한다.

---

## 팀 확정 게이트 (헌법 30조)

speckit 기준의 "미해결 NEEDS CLARIFICATION"으로 남기지 않기 위해 명시한다 — 아래 4건은 **BE가 확정할 수 없는 항목**이고(앞 2건은 팀 미결 결정, 뒤 2건은 C-01 확정에 딸려 오는 문서 정정), 이 plan은 결정을 선점하는 대신 **확정 절차를 완료 조건에 내장**한다.

| # | 항목 | 이 plan의 제안 | 확정 주체 | 처리 |
|---|---|---|---|---|
| C-01 | URL 저장 위치 | `booths` 컬럼, Layout 불변 (R-01) | FE + BE + Unity | 추적 이슈 + docs/26 기입 — **초안 작성 후 사용자 승인 하에 게시** |
| C-02 | 부스당 개수 | 1개 (R-02) | 기획(+3파트) | C-01과 같은 이슈에서 함께 |
| FR-011 | C-01과 양립하는지 | C-01로 가면 *"계약 확장이 필요하다"* 가 성립하지 않는다. **`spec.md`는 리드 문서라 손대지 않았다** | 리드(spec 저자) | C-01과 같은 이슈에서 함께 묻는다. 상세는 [계약 초안](../contracts/homepage-api.md) §6-6 |
| docs/26 | 미결 행의 전제 | FE 의견의 *"컬럼 신규 추가"* 와 실측이 다르다 — 컬럼은 V1부터 있다. **팀 정본이라 손대지 않았다** | 행 소유자 (#59) | 같은 이슈에서 어떻게 할지 묻는다. 상세는 §6-7 |

구현 자체는 전부 가산적(기존 컬럼 + 응답 필드 추가 + endpoint 신설)이라 확정 대기와 병행해도 뒤집힐 때 잃는 폭이 작다.

> **머지 정책 정정 (2026-08-25)** — 이 절은 처음에 *"확정 전 back 머지 금지"* 로 적혀 있었다. 그 의도는 "제안이 확정처럼 읽히는 것"을 막는 것이었는데, 실제 결과는 **+443줄이 워크트리 전용 브랜치에만 남는 유실 위험**(T-117 계열)이었다. 그래서 기준을 바꾼다 — **문서는 `제안` 표기를 유지한 채 머지하고, 구현(코드) 착수만 확정 후로 묶는다.** 확정되면 계약 초안 헤더와 이 표를 갱신한다.

**BE 무관 미결** (이 plan이 다루지 않는 것): C-03(삽입 불가 대안 폭)·C-04(3D 연출)·C-06(노트북 에셋)·C-07(열람 중 조작 잠금) — FE·Unity·기획 몫. iframe 차단 감지·새 탭 fallback·혼합콘텐츠 경고는 전부 React 레이어다(헌법 25조, spec FR-005·FR-007).

**부수 정리 후보** (확정 통보에 질문으로 포함, 기본은 현행 유지): `LayoutObjectType.LAPTOP`의 `requiresConfig=true`는 "configId 미연결 경고(CONFIG_NOT_LINKED)" 대상인데, URL이 부스 단위로 확정되면 LAPTOP은 연결할 configId가 애초에 없어 **경고가 상시 오탐**이 된다. 팀 답에 따라 `false` 전환(1줄+테스트) 또는 "부스에 URL 미등록 + LAPTOP 존재 → 경고" 재해석을 후속으로 다룬다.
