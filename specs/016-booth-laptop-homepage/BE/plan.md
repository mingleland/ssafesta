# Implementation Plan: 부스 홈페이지 URL (016 BE분)

**Branch**: `docs/S15P21A604-109-homepage-spec` (origin/develop 기준) | **Date**: 2026-08-24 (확정 반영 2026-08-27) | **Spec**: [spec.md](../spec.md)

**Input**: `specs/016-booth-laptop-homepage/spec.md` + `docs/sdd/parts/BE.md` §016 + `docs/26` "LAPTOP 홈페이지 주소 저장 위치" 행(FE 의견) + `docs/sdd/parts/FE.md` §016(브리지 왕복 검증 기록)

## Summary

부스 노트북 기능의 서버 몫 — 지금 코드에는 `booths.homepage_url` 컬럼과 매핑만 있고 **등록도 노출도 검증도 없다**(`getHomepageUrl` 사용처 0건). **`PUT /api/v1/booths/{boothId}/homepage`** 로 등록·수정·해제하고, **`GET /booths/{boothId}`(방문자)·`GET /booths/mine`(소유자) 응답에 `homepageUrl`을 추가**해 FE 오버레이가 소비할 값을 연다.

설계의 중심은 세 가지다.

1. **Layout 계약을 건드리지 않는다** (C-01 — **2026-08-26 확정**, #97). URL은 부스 단위 1개(C-02, 같이 확정)로 `booths` 컬럼에 산다. `LAPTOP` 타입·`BOOTH_LAPTOP_INTERACT{boothId, objectId, url?}` 브리지는 이미 확정·검증된 계약이라 **Unity 쪽 변경이 0**이다. FE는 브리지로 받은 `boothId`로 공개 조회를 호출하게 되는데, **그 조회 경로는 지금 FE에 없어 신설 대상**이다(계약 §4 정정). C-01 확정으로 **구현(코드) 착수 게이트가 풀렸다** — 아래 §Phase 2 참조.
2. **facade 선례의 반복이다.** `PUT /booths/{id}/facade`가 확정해 둔 규칙(에디터 가드 + 유효 임대 + 검증 + `booths` 직접·즉시 반영 + `BOOTH_LEASE_EXPIRED`)을 그대로 탄다. 스키마 작업 0(V1 컬럼 기존), 신규 오류 어휘 0(#58 봉투 + `FIELD_INVALID`).
3. **노출은 published 게이트로 건다** (FR-003). `publishedLayoutVersion == null`이면 방문자 응답에서 `homepageUrl`도 null — 노트북이 공개 Layout 안에만 존재하므로 게이트와 실사용 순간이 일치한다. 검증은 위임받은 두 가지(http/https 스킴 + 길이 2048)이고 사유별 다른 문장으로 거부한다(T-24: 실패를 조용히 삼키지 않는다). **요청 DTO는 `homepageUrl` 키의 존재 여부를 추적한다** — `record`는 `{}`와 `{"homepageUrl":null}`을 구분하지 못해 FE 직렬화 실수가 조용한 해제가 된다(research R-04 ⓪, data-model §3 #0). 검증 순서에서 **scheme을 host보다 먼저** 본다 — `javascript:`가 host 부재로 먼저 걸리면 스킴 위반 사유가 전달되지 않는다.

결정 근거 전체: [research.md](research.md) R-01~R-10.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA, Spring Security (Resource Server), Jackson. 재사용: `BoothEditorGuard`(소유자+스태프) · `BoothPrincipal`(게스트 차단) · `leases.findValidByBoothId`(만료 판정) · `ApiException`/`ErrorCode`/`ApiErrorDetail.field()`(#58·T058 봉투) · `java.net.URI`(형식 검증)

**Storage**: PostgreSQL 17 — `booths.homepage_url VARCHAR(2048)` (V1부터 존재). **신규 마이그레이션 없음**

**Build/Testing**: **Maven**(`backend/mvnw`, Spring Boot 4.1.0 parent). JUnit 5, Testcontainers(PostgreSQL), MockMvc — 003·004·005·013a와 동일 패턴. 신규 통합 테스트 1개(`BoothHomepageApiIntegrationTest`) + `LayoutValidator` LAPTOP 경고 재해석 회귀(기존 테스트 갱신). 회귀 기준선은 착수 시 **origin/develop**에서 실측해 tasks.md에 기록

**Target Platform**: Docker Spring API (`backend/`)

**Project Type**: Web API — 기존 모놀리스에 endpoint 1개 + 응답 필드 2곳 추가

**Performance Goals**: 단건 PK 행 UPDATE/SELECT. 등록은 스튜디오에서 가끔, 노출은 기존 부스 조회에 편승 — 인덱스·캐시 불필요

**Constraints**: 왕복 무손실(저장 바이트 = 반환 바이트, data-model §2) · 미등록/미공개는 오류가 아니라 `null`(FR-009 — FE가 안내로 처리) · 모든 거부에 사유 문장(사유별 상이)

**Scale/Scope**: 부스당 1값. endpoint 1개 + 응답 필드 2곳 + 서비스/컨트롤러 각 1개 + 테스트 1개

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 후 재확인 — **PASS** (아래 표, 위반 0건)*

| 조 | 요구 | 이 계획에서 |
|---|---|---|
| 1 | Booth 영구 상태의 SoT는 Spring | 저장·노출 모두 `booths.homepage_url` 단일 지점. Unity·React는 조회만 |
| 4 | Booth는 데이터로 생성 — 재빌드 금지 | URL은 순수 데이터. `LAPTOP` 프리팹·브리지는 기존 그대로, Unity 변경 0 |
| 16 | 클라이언트 주장 불신 | 편집 권한은 JWT + `BoothEditorGuard`로 서버가 판정. 값은 스킴 화이트리스트(`javascript:` 차단)·길이 게이트 |
| 24 | 계약 변경은 합의로만 | 전부 **가산적**(endpoint 신설 + 응답 필드 추가) — Breaking 없음. 확정 절차 완료: C-01·C-02 확정 요청 이슈 **#97 게시·종료(2026-08-26)**, 3파트 합의 확보. 남은 것은 `docs/08` §3 갱신 |
| 25 | 텍스트 입력·외부 페이지는 React | URL 입력 폼·iframe 오버레이·차단 감지·새 탭 fallback·혼합콘텐츠 경고 전부 FE 몫(FR-005·FR-007). BE는 저장·노출만. Unity는 트리거만(기존 브리지) |
| 29 | 기록 의무 | `docs/HDD/작업일지.md` + 트러블 시 T-번호 |
| 30 | 미정 항목 임의 확정 금지 | C-01·C-02를 BE가 확정하지 않고 **#97로 팀에 올려 확정받았다**(2026-08-26 종료, 리드 확정 + FE 동의). 파생 4건(FR-003 해석·http 유지·`requiresConfig` 재해석·FR-011 문구)도 같은 이슈에서 답을 받았다 — 계약 §6. C-05(악성 링크)는 기존 팀 결정(U-01 종속, 분리 설계 금지)을 따라 범위 제외(R-09). C-03·C-04·C-06·C-07은 BE 무관 |

## Project Structure

### Documentation (this feature)

```text
specs/016-booth-laptop-homepage/
├── spec.md                      # 리드 초안 (Draft — 리뷰칸 미기입, BE 몫은 quickstart §4에서 기입)
├── contracts/
│   └── homepage-api.md          # 계약 정본 — 확정 (2026-08-26, #97)
└── BE/                          # BE 실행 산출물 (#43 구조, 013a BE/와 대칭)
    ├── plan.md                  # 이 파일
    ├── research.md              # Phase 0 — R-01~R-10 + 팀 확정 게이트
    ├── data-model.md            # Phase 1 — 스키마 0·검증 규칙·노출 규칙·불변식
    ├── quickstart.md            # Phase 1 — 검증 절차·통보 체크리스트
    └── tasks.md                 # Phase 2 — /speckit-tasks 산출 (아직 없음)
```

### Source Code (repository root)

```text
backend/src/main/java/com/example/ssafesta/booth/
├── BoothHomepageController.java  # [신설] PUT /booths/{boothId}/homepage (BoothFacadeController 대칭)
├── BoothHomepageService.java     # [신설] 가드 → 유효 임대 → URL 검증 → 변경 (BoothFacadeService 대칭)
├── Booth.java                    # [수정] changeHomepageUrl() 도메인 메서드 (컬럼·매핑은 기존)
├── BoothQueryService.java        # [수정] PublicBoothView(published 게이트)·MyBoothView에 homepageUrl
└── LayoutValidator.java          # [수정] LAPTOP 의 CONFIG_NOT_LINKED 판정을 configId → URL 미등록으로 (계약 §3-1)

backend/src/test/java/com/example/ssafesta/booth/
└── BoothHomepageApiIntegrationTest.java  # [신설] quickstart §3 시나리오 전부
```

**건드리지 않는 것**: 마이그레이션(신규 없음) · `SecurityConfiguration`(PUT 경로는 `anyRequest().authenticated()`에 이미 걸린다, `GET /booths/*`는 이미 permitAll) · `BoothFacadeService`("4필드" 계약 유지) · `LayoutJson`(URL은 Layout에 들어가지 않는다) · `LayoutObjectType`(아래) · Unity·FE 코드(통보만).

> **`LayoutObjectType.LAPTOP`의 `requiresConfig`는 `true`로 유지한다** — 확정된 것은 플래그 전환이 아니라 **판정 재해석**이다(계약 §3-1).
> 초안은 *"`requiresConfig()`는 `LayoutValidator` 한 곳에서만 쓰이므로 `false`로 내려도 다른 검증에 영향이 없다"* 고 적었는데 **실측이 틀렸다.** develop 기준 호출처는 **2곳**이다:
> - `LayoutValidator.java:221` — `CONFIG_NOT_LINKED` 경고 (의도한 대상)
> - `LayoutPassageChecker.java:80` — `checkViewingBand()` 호출 조건 (**의도하지 않은 대상**)
>
> `false`로 내렸다면 LAPTOP이 **시야 확보 검사에서 통째로 빠져** 방문자가 앞에 설 자리가 없는 배치를 아무도 경고하지 않게 됐다. 재해석안은 플래그를 건드리지 않으므로 이 부작용이 없다 — 결과적으로 FE가 선호한 쪽이 옳았다.

## API 형태 (이 plan의 제안 — contracts/homepage-api.md가 정본)

```http
PUT /api/v1/booths/{boothId}/homepage
Authorization: Bearer <member access token>
{ "homepageUrl": "https://my-team-project.example.com" }     # null = 등록 해제

→ 200 { "homepageUrl": "https://..." }                        # echo — 저장한 그대로
→ 400 VALIDATION_FAILED + FIELD_INVALID/field:"homepageUrl"   # blank·2049자↑·형식·스킴, 사유별 다른 문장
→ 401 / 403 BOOTH_EDITOR_FORBIDDEN / 404 BOOTH_NOT_FOUND / 409 BOOTH_LEASE_EXPIRED
```

```http
GET /api/v1/booths/{boothId}    # 인증 불필요 (기존)
→ 200 { ..., "publishedLayoutVersion": 4, "homepageUrl": "https://..." | null }
                                 # publishedLayoutVersion == null 이면 homepageUrl 도 null (FR-003)

GET /api/v1/booths/mine
→ 200 { ..., "homepageUrl": "https://..." | null }            # 게이트 없음 — 스튜디오 프리필
```

별도 `GET /booths/{id}/homepage`·Published Layout 응답 포함은 **만들지 않는다** — 소비자가 없다(R-05).

## Complexity Tracking

없음. 신규 층·신규 오류 어휘·신규 스키마 0. facade 패턴(가드·만료·즉시 반영·검증 순서·봉투)의 반복이라 복잡도 추가 요인이 없다.

## Phase 2 준비 상태

- [x] Phase 0 research.md — R-01~R-10, 팀 확정 게이트 2건(C-01·C-02) 절차 내장
- [x] Phase 1 data-model.md · contracts/homepage-api.md · quickstart.md
- [x] Constitution 재확인 — PASS
- [x] **C-01·C-02 확정 요청 — [#97](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/97) 게시·종료 (2026-08-26).** 파생 5건까지 회신 완료, 계약 §6에 반영
- [ ] `/speckit-tasks`로 tasks.md 생성 → **구현 착수** (게이트 해제됨 — 확정 완료)
- [ ] `docs/08` §3(Booth)에 endpoint·응답 필드 반영
- [ ] spec.md FR-011 문구 정정 — 리드 확정문 있음(계약 §6), **리드 문서라 BE가 직접 고치지 않는다**
