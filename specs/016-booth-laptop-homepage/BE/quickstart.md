# Quickstart: 016 부스 홈페이지 URL 검증 (BE분)

**Phase 1** | 2026-08-24 | 계약: [contracts/homepage-api.md](../contracts/homepage-api.md) · 규칙: [data-model.md](data-model.md) §3·§4

## 전제

- Docker 실행 중 (Testcontainers가 PostgreSQL 17을 띄운다 — 003·004·005·013a와 동일)
- 저장소 루트에서 실행

## 1. 통합 테스트

```bash
cd backend && ./mvnw test -Dtest=BoothHomepageApiIntegrationTest
```

전체 회귀:

```bash
cd backend && ./mvnw test
```

**기대**: 전부 green. 기존 테스트가 하나도 깨지지 않아야 한다 — `PublicBoothView`·`MyBoothView` 필드 추가와 endpoint 신설은 전부 가산적 변경이다. (착수 시 origin/back 기준선 개수를 먼저 실측해 tasks.md에 기록한다.)

## 2. 수동 확인 (선택)

```bash
# 등록
curl -i -X PUT localhost:8080/api/v1/booths/7/homepage \
  -H "Authorization: Bearer <owner-access-token>" -H "Content-Type: application/json" \
  -d '{"homepageUrl":"https://my-team-project.example.com"}'
# → 200 {"homepageUrl":"https://my-team-project.example.com"}

# 방문자 노출 — 공개(published) 부스면 값, 미공개면 null
curl -s localhost:8080/api/v1/booths/7
# → { ..., "publishedLayoutVersion": 4, "homepageUrl": "https://..." }

# 거부 — 스킴 위반
curl -i -X PUT localhost:8080/api/v1/booths/7/homepage \
  -H "Authorization: Bearer <owner-access-token>" -H "Content-Type: application/json" \
  -d '{"homepageUrl":"javascript:alert(1)"}'
# → 400 VALIDATION_FAILED, errors[0] = { rule: FIELD_INVALID, field: "homepageUrl", ... }

# 해제
curl -i -X PUT localhost:8080/api/v1/booths/7/homepage \
  -H "Authorization: Bearer <owner-access-token>" -H "Content-Type: application/json" \
  -d '{"homepageUrl":null}'
# → 200 {"homepageUrl":null}
```

## 3. 시나리오 ↔ 근거 매핑

통합 테스트가 아래를 전부 커버해야 구현 완료다. (전체 목록과 순서는 tasks.md가 확정한다.)

| 시나리오 | 근거 |
|---|---|
| 소유자 `PUT` 200 + echo → `GET /booths/mine` 왕복 **바이트 동일** | US2-1, data-model §2 불변식 |
| 스태프 `PUT` 200 (편집자 범위 = facade·layout과 동일) | R-03, spec 005 FR-012 |
| `http://` 통과 · `https://` 통과 | FR-002, R-04 (http 허용은 의도) |
| `javascript:`·`ftp:`·상대경로·host 없음 → 400, 사유별 다른 문장 | US2-2, R-04, 헌법 16조 |
| 경계: 2048자 통과 / 2049자 400 | data-model §3 #2 |
| `""` 400 / `null` 200 해제 → 미등록 상태 복귀 | data-model §5 |
| 400 봉투: `VALIDATION_FAILED` + `FIELD_INVALID`/`field: "homepageUrl"` | R-07, docs/08 §1.3-1 |
| 미인증 401 / 비편집자 403 `BOOTH_EDITOR_FORBIDDEN` / 없는 부스 404 | R-07 |
| 임대 만료 부스 `PUT` → 409 `BOOTH_LEASE_EXPIRED` | R-03, Edge Case(만료 직후), facade와 동일 규칙 |
| **미공개 부스**(publishedLayoutVersion null): 등록돼 있어도 public view `homepageUrl: null` | **FR-003**, R-05 게이트 |
| **공개 부스**: public view에 저장값 노출 | US1-1, R-05 |
| 공개 부스라도 `GET /booths/mine`은 항상 저장값 (프리필) | R-06 |
| Published Layout 응답에 URL 없음 (Layout 계약 불변) | R-01, data-model §4 |
| **LAPTOP 있음 + URL 미등록 → Publish warning `CONFIG_NOT_LINKED`** ("홈페이지 주소가 등록되지 않았습니다.") | **R-10**, 계약 §3-1 |
| LAPTOP 있음 + URL 등록 → 경고 없음 (기존 `configId` 기준 오탐이 사라진다) | R-10 |
| LAPTOP 없음 + URL 미등록 → 경고 없음 | R-10 |
| `requiresConfig`는 `true` 유지 — `LayoutPassageChecker` 시야 확보 검사 회귀 없음 | R-10 실측 정정 |

## 4. 완료 후 절차 (헌법 24조·29조·30조)

> ⚠️ 아래 중 밖으로 나가는 것(이슈·코멘트·docs 정본 기입·push)은 **전부 초안까지만 만들고 사용자 승인 후 게시한다.**

- [x] **C-01·C-02 확정 요청** — [#97](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/97) 게시 → **2026-08-26 종료.** 물은 7건 전부 회신, 결과는 [contracts §6](../contracts/homepage-api.md)
- [x] `contracts/homepage-api.md` 상태줄 갱신 (제안 → **확정**, 2026-08-27)
- [ ] `docs/26` "LAPTOP 홈페이지 주소 저장 위치" 행에 확정값 기입 — **FE(행 작성자)가 정정 담당**(오기 자인). stale 행 1건(`BOOTH_LAPTOP_INTERACT` 미검증 표기)도 같이. BE는 미착수 시 상기만 한다
- [ ] 파트 통보 — FE: `PUT /booths/{id}/homepage` 신설 + `homepageUrl` 필드 2곳(public·mine) 추가(가산적) + published 게이트·null 의미. Unity: 변경 없음(브리지·Layout 그대로) — 참고 통보만
- [ ] `docs/08` §3(Booth)에 endpoint·응답 필드 반영
- [ ] spec 016 리뷰칸(①C-01·C-02 답 ③빠진 요구 ④계약 위치 합의)의 BE 몫 기입
- [x] ~~구현(코드) 착수는 C-01·C-02 확정 후에만~~ — **게이트 해제 (2026-08-26 확정).** 구현 착수 가능
- [ ] FE 통보 추가분 — 스튜디오 2건(`LAPTOP`에 `configId` 미전송 · `objectTypes.ts:36` `warnOnMissingConfig` → `false`) + 오버레이 조회 전환 3건 (계약 §3-1·§4)
- [ ] `docs/HDD/작업일지.md` 기록, 문제 발생 시 트러블슈팅 T-번호
