# Quickstart: Booth Layout 검증 (spec 005)

**Date**: 2026-08-20 | **Phase**: 1

구현이 요구사항을 실제로 만족하는지 확인하는 절차다. 계약 세부는 [contracts/layout-api.md](../contracts/layout-api.md), 제약·불변식은 [data-model.md](data-model.md)를 본다.

---

## 0. 전제

- **Docker Desktop 기동** — Testcontainers가 PostgreSQL(pgvector)·Redis를 띄운다. 안 켜져 있으면 T-97이 재현된다.
- PostgreSQL 이미지는 `pgvector/pgvector:pg17`이어야 한다. `postgres:17-alpine`이면 V1의 `CREATE EXTENSION vector`가 실패한다 (T-98).
- 004까지의 마이그레이션(V1~V7)이 적용된 상태.

---

## 1. 자동 검증

```bash
cd backend && ./mvnw test
```

기준: **기존 103건 + 005 신규분이 전부 통과**, Failures 0 / Errors 0.

| 테스트 | 무엇을 지키는가 |
|---|---|
| `BoothLayoutServiceIntegrationTest` | 저장 → 공개 → 포인터 전이가 한 트랜잭션 (I-7, data-model §4) |
| `BoothLayoutRoundTripIntegrationTest` | **저장한 좌표가 조회에서 그대로 나온다** (SC-004, R-04) |
| `BoothLayoutValidationTest` | 12개 초과·중복 objectId·미지원 type이 `errors`로, 미연결 configId가 `warnings`로 (FR-016) |
| `BoothLayoutConcurrencyIntegrationTest` | 같은 부스에 동시 저장 → 하나만 성공, 나머지는 409 (FR-014, I-6) |
| `BoothLayoutApiIntegrationTest` | 권한·만료·404/409 (FR-012, FR-015) |
| `BoothLayoutReleaseIntegrationTest` | 임대 만료·재연결 시 공개본 포인터가 끊긴다 (FR-011·FR-017) |

### 왕복 무손실 테스트가 실제로 확인해야 하는 것

`2.1`을 저장하고 `2.1`이 나오는지만 보면 부족하다. **BE가 중간에 `double`로 파싱해 재직렬화하면 이 테스트가 통과하면서도 값이 상한다.** 다음을 포함한다.

- 소수점 아래가 긴 값 (`2.123456789`)
- 음수·`0.0`·`-0.0`
- `rotationY: 359.9`
- 알 수 없는 필드가 섞인 요청 → **저장 거부 여부가 명시적**일 것 (조용히 버리지 않는다 — T-24의 교훈)

---

## 2. 수동 검증 (Bruno `05-booth-layout`)

003·004와 같은 방식이다. Access Token은 `01-auth`가 환경변수에 심어 둔 것을 쓴다.

### 순서

| # | 요청 | 기대 |
|---|---|---|
| 1 | `01-auth` → 로그인 | Access Token 확보 |
| 2 | `04-booth-lease` → 슬롯 임대 | `boothId` 확보 (없으면 이후 전부 404) |
| 3 | `GET /booths/{id}/layouts/draft` | **204** — 아직 작업본 없음 |
| 4 | `PUT …/draft` (`expectedRevision: 0`, 오브젝트 2개) | 200, `revision: 1` |
| 5 | `GET …/published` | **404 `LAYOUT_NOT_PUBLISHED`** — 저장만으로는 공개되지 않는다 (**SC-003 핵심**) |
| 6 | `PUT …/draft` (`expectedRevision: 0` 다시) | **409 `LAYOUT_REVISION_CONFLICT`** + 본문에 최신 Draft |
| 7 | `POST …/publish` | 200, `publishedVersion: 1` |
| 8 | `GET …/published` (**토큰 없이**) | 200, 4번에서 보낸 좌표와 **문자 그대로 같은 값** |
| 9 | `PUT …/draft` (오브젝트 1개로 축소, `expectedRevision: 1`) | 200, `revision: 2` |
| 10 | `GET …/published` | **여전히 오브젝트 2개** — 공개본은 스냅샷이다 (FR-006, I-7) |
| 11 | `PUT …/draft` (오브젝트 13개) | **409 `LAYOUT_VALIDATION_FAILED`**, `errors[].rule = OBJECT_LIMIT` |
| 12 | 다른 계정 토큰으로 `PUT …/draft` | **403 `BOOTH_EDITOR_FORBIDDEN`** |
| 13 | `PUT /booths/{id}/facade` (`primaryColor: "#3b82f6"` — **소문자로**) | 200, 응답의 `primaryColor`가 **`#3B82F6`**(대문자 정규화, #17) |
| 13-1 | 같은 요청을 `primaryColor: "#123456"`으로 | **400 `VALIDATION_FAILED`**, 메시지가 형식 오류가 아니라 `팔레트에 없는 색입니다.` |
| 14 | `GET /booths/{id}` | `facade` 4필드 + `publishedLayoutVersion: 1` |

### 만료 경로

임대를 만료시키는 방법은 004 quickstart와 같다 (`ends_at`을 과거로 직접 갱신).

| # | 요청 | 기대 |
|---|---|---|
| 15 | `GET …/published` | **409 `BOOTH_LEASE_EXPIRED`** (FR-015) |
| 16 | `POST …/publish` | **409 `BOOTH_LEASE_EXPIRED`** — 만료 부스는 공개할 수 없다 |
| 17 | `GET …/draft` (owner) | **200** — 소유자의 작업본은 계속 열린다 (FR-011: 보존) |
| 18 | 같은 슬롯을 다시 임대 후 `GET /booths/{id}` | `publishedLayoutVersion: null` (FR-017) |

---

## 3. Unity 연동 확인 (선택 — Unity 환경이 있을 때)

`HttpBoothApiClient`가 이미 `/api/v1/booths/{boothId}/layouts/published`를 호출한다. `ApiConfig.asset`의 `useMockApi`를 끄고 Base URL을 로컬 Spring으로 두면 **BE 코드 변경 없이** 실물 경로가 연결된다.

| 확인 | 근거 |
|---|---|
| 오브젝트가 생성된다 | spec 006 `BoothRuntime` |
| 공개본이 없는 부스에서 클라이언트가 죽지 않는다 | 404 → 경고 후 graceful skip (기존 동작) |
| 모르는 `type`이 섞여도 나머지가 뜬다 | SC-005 |

**좌표 부호·원점 일치는 여기서 확인되지 않는다.** 그건 spec 005 리뷰 ④칸의 왕복 검증(FE ↔ Unity)이며 BE 단독으로 닫을 수 없다.

---

## 4. 검증이 끝나면

- `docs/HDD/작업일지.md`에 결과 기록, 문제는 해결 여부와 무관하게 `docs/HDD/트러블슈팅.md`에 T-번호로 등록 (헌법 29조)
- `docs/08`에 `PUT /booths/{boothId}/facade` 반영 (신설 endpoint 통보 — 헌법 24조)
- `docs/09 §7·§9`를 실물 스키마에 맞게 정정 (research R-01·R-08)

---

## 5. 부스 치수 확장 확인 (`S15P21A604-698`)

`9.4 × 6 × 5.9` 로 넓힌 뒤, **넓어진 것과 넓어지지 않은 것이 각각 그대로인지** 본다.

```bash
cd backend
./mvnw test -Dtest=BoothLayoutValidationTest,BoothLayoutApiIntegrationTest
```

| 확인 | 어디서 |
|---|---|
| 앵커 x = 4.7 통과 / 4.8 거부 | `BoothLayoutValidationTest.theAnchorBoundIsWiderOnXThanOnZ` |
| 앵커 z = 3.0 통과 / 3.1 거부 — **x 가 넓어졌다고 z 가 열리면 안 된다** | 같은 테스트 (`z = 4.7` 도 거부) |
| 실물(회전 AABB)이 축별 경계를 각자 본다 | `theExtentBoundIsWiderOnXThanOnZ` · `anExtentTouchingTheWallsIsAllowed` |
| 회전 뒤에도 판정이 **월드 축** 기준 — 로컬 x 가 월드 z 를 채울 때 x 경계가 z 로 새지 않는다 | `rotationMovesTheExtent` |
| 높이는 실물 **상단**으로 판정 (5.9 통과 / 5.91 거부) | `theCeilingIsJudgedOnTheObjectTop` |
| 카탈로그가 `width: 9.4` · `depth: 6.0` · `height: 5.9` | `BoothLayoutApiIntegrationTest` (검증 상수에서 유도되므로 상수만 고치면 따라온다) |
| 통행 격자가 실제로 넓어졌다 — 옛 6m 벽은 이제 부스를 가로막지 못한다 | `BoothLayoutPassageTest.theOldSixMetreWallNoLongerCrossesTheBooth` |
| 기존 6m 안쪽 배치에 회귀 없음 | 백엔드 전체 `./mvnw test` |

**수동으로 한 번 볼 것**: 편집기(FE)를 로컬 서버에 붙여 캔버스 우하단 치수가 `9.4 × 6 × 5.9 m` 로 뜨는지. FE 는 값을 하드코딩하지 않고 `template.footprint` 에서 받으므로 이 숫자가 **서버 응답 실측값**이다.

**Consumer 반영 상태를 확인하기 전에는 develop 에 넣지 않는다** (헌법 24조). FE 는 `S15P21A604-785` 로 먼저 들어갔다 — 순서가 반대가 되면 사용자가 편집기에서 허용된 자리에 다 꾸며 놓고 게시 버튼에서 막힌다.
