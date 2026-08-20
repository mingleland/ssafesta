# Research: Booth Studio / Layout 계약 (spec 005)

**Date**: 2026-08-20 | **Phase**: 0

각 항목은 **결정 / 근거 / 버린 대안**이다. 코드 주석에서 `R-xx`로 참조한다.

---

## R-01. 저장 구조 — V1 2테이블을 유지하고 docs/09를 정정한다

**결정**: `booth_layout_drafts`(PK=`booth_id`) + `booth_layout_published_versions`(`UNIQUE(booth_id, version_no)`) 그대로 간다. 새 테이블을 만들지 않는다.

**근거**: 작업본은 부스당 **하나**여야 하는데(FR-005), PK가 `booth_id`면 그게 스키마로 강제된다. 공개본은 **여러 개**여야 이력(C-07)이 남는다. 두 요구가 다르므로 테이블이 둘인 게 자연스럽다.

**버린 대안**: docs/09 권장안 A의 단일 `booth_layouts` + `state DRAFT/PUBLISHED/ARCHIVED`. 이 구조는 **DRAFT 행이 둘 생기는 것을 막지 못한다** — 막으려면 `UNIQUE(booth_id) WHERE state='DRAFT'` 부분 인덱스를 따로 얹어야 하는데, 그러면 V1 구조와 표현력이 같아지면서 상태 전이 코드만 늘어난다. 004의 T-110이 정확히 "불변식을 앱 로직으로만 지키다 뚫린" 사례다.

**따라오는 일**: `docs/09 §9`를 실물에 맞게 정정한다 (tasks 포함).

---

## R-02. 공개본 지시 — `booths.published_layout_version` 컬럼 (NULL 허용)

**결정**: `booths`에 `published_layout_version INTEGER NULL`을 추가하고(V8), 공개 트랜잭션에서 함께 갱신한다.

**근거**: **FR-011/FR-017을 표현할 수 있는 유일한 형태다.** 재임대(또는 임대 만료 후 재연결) 시 포인터를 `NULL`로 두면 "보존된 콘텐츠는 작업본 상태로 시작"이 데이터로 성립한다. docs/08의 `publishedLayoutVersion` 응답 필드와도 일치한다.

**버린 대안**: `MAX(version_no)` 유도. 조회는 되지만 **"공개된 것이 없음"을 표현할 수 없다.** 재임대하면 이전 소유자가 공개했던 마지막 버전이 그대로 되살아난다 — FR-011 위반이고, 조회 시점 판정으로는 되돌릴 수 없다.

---

## R-03. 동시 편집 — 낙관적 잠금 (`revision`)

**결정**: `PUT draft`는 `expectedRevision`을 필수로 받는다. `booth_layout_drafts.revision`과 다르면 `409 LAYOUT_REVISION_CONFLICT`. 저장 성공 시 `revision + 1`.

**근거**: V1에 `revision BIGINT NOT NULL DEFAULT 0`이 이미 있다. 편집자가 owner + staff 소수라 충돌 빈도가 낮고, 충돌했을 때 **잃는 것이 없다**(거부되고 다시 불러오면 된다).

**버린 대안**:
- *마지막 저장 우선* — 남의 작업을 조용히 지운다. 사용자는 무엇을 잃었는지도 모른다.
- *비관적 잠금(편집 중 점유)* — 잠근 채 브라우저를 닫으면 아무도 못 고친다. 해제 타임아웃·강제 해제·"누가 잠갔는지" UI가 전부 새 요구사항이 된다. 임대 만료(24h)보다 긴 잠금이 남을 수도 있다.

**FE에 남는 결정**: 충돌 시 다시 불러올지 병합 UI를 줄지는 FE 몫이다. BE는 현재 `revision`과 최신 Draft를 응답에 함께 실어 둘 다 가능하게 한다.

---

## R-04. JSONB 왕복 — "무손실"은 바이트가 아니라 **의미** 단위다

**결정**: `layout_json`은 `JSONB` 유지. 왕복 보장의 정의를 **의미 동등**으로 못 박고 테스트한다 — 키 집합·중첩 구조·**수치 값이 정확히 같을 것**. BE는 좌표를 `double`로 파싱해 되돌려 쓰지 않는다.

**근거**: PostgreSQL `jsonb`는 저장 시 **키 순서를 정규화하고 공백을 제거한다.** 바이트 단위 보존은 애초에 불가능하므로 "무손실"을 그렇게 정의하면 시작부터 거짓이 된다. 반대로 수치는 `numeric`으로 저장돼 **정밀도 손실이 없다** — 손실이 생긴다면 그건 DB가 아니라 **BE가 중간에 `double`로 파싱해 재직렬화했을 때**다. 그래서 위험 지점은 저장소가 아니라 코드다.

검증(범위·유한값)에는 `BigDecimal`을 쓰고, 저장은 요청 본문을 그대로 넘긴다.

**버린 대안**: `TEXT` 컬럼으로 바꿔 바이트를 보존. V1·docs/09가 선택한 JSONB를 뒤집는 변경인데, 얻는 것이 "키 순서 보존"뿐이다. 키 순서는 어느 파트도 의미로 쓰지 않는다.

---

## R-05. 검증 — `errors`(차단)와 `warnings`(허용)를 나눈다

**결정**: 검증기는 두 목록을 반환한다. `errors`가 하나라도 있으면 공개를 거부한다(FR-007). Draft 저장에는 **구조·상한 규칙만** 적용하고, 콘텐츠 연결 같은 완성도 규칙은 경고로 흘린다.

**근거**: C-04(미연결 오브젝트의 공개를 막는가)가 기획 미정이다. 두 목록으로 나눠 두면 **확정이 항목을 한 리스트에서 다른 리스트로 옮기는 일**이 되어 응답 형태·FE 파서가 그대로 산다 (헌법 30조를 지키면서 착수하는 방법).

Draft 저장에도 상한 12개를 거는 이유는 따로 있다 — 공개 시점에만 걸면 FE 버그로 200개짜리 작업본이 저장돼 `layout_json`만 부푼다. 사용자는 공개를 눌러야 비로소 알게 된다.

**버린 대안**: 단일 `errors` 목록 + 확정 전까지 미연결을 그냥 통과. 확정되는 순간 응답 형태가 바뀌어 FE가 다시 작업한다.

---

## R-06. 만료 부스의 공개본 — 술어를 복제하지 않는다

**결정**: `GET /layouts/published`는 004의 `BoothLeaseRepository.findValidByBoothId(boothId, now)`를 그대로 호출하고, 없으면 `409 BOOTH_LEASE_EXPIRED`. 새 만료 판정을 쓰지 않는다.

**근거**: 004 구현 정리 §3에 남긴 위험 그대로다 — 만료 술어(`status='ACTIVE' AND ends_at > now()`)가 여러 곳에서 하중을 받는데, 한 곳에서 시간 조건을 빠뜨리면 **조용히 틀린다.** Layout 조회는 그 네 번째 지점이다.

**버린 대안**: Layout 쪽에서 `lease.endsAt`을 읽어 직접 비교. 같은 술어의 사본이 하나 더 생긴다.

---

## R-07. 편집 권한 — owner + `booth_staffs` 조회, 초대 흐름은 011

**결정**: `BoothEditorGuard`가 `Booth.isOwnedBy(userId) || boothStaffs.exists(boothId, userId)`로 판정한다. `booth_staffs`는 **읽기만** 한다.

**근거**: FR-012가 "소유자·권한 있는 Staff"를 요구하고 `booth_staffs(booth_id, user_id, role)`가 V1에 이미 있다. 초대·수락·역할 부여는 spec 011(staff-consultation) 소유이므로 005가 그 흐름을 만들면 나중에 두 번 만든다. 지금은 **행이 있으면 편집 가능**으로 충분하다 — 011이 행을 만드는 방법을 정하면 005는 그대로 동작한다.

**버린 대안**: 005에서 초대까지 구현. 범위 침범이고 011의 역할 모델(`role` 값 집합)이 아직 없다.

---

## R-08. facade — docs/08·09 설계 문서대로 4필드 (2026-08-20 결정)

**결정**: V9에서 `facade_code`를 `facade_theme_code`로 **rename**하고 `facade_primary_color` · `facade_sign_text` · `facade_logo_url`을 추가한다. 조회 응답은 docs/08의 `facade{themeCode, primaryColor, signText, logoUrl}` 그대로.

**근거**: docs/08(API)·docs/09(DB) 두 문서가 이미 4필드로 합의돼 있고 DB만 단일 컬럼으로 뒤처져 있었다(U-05). rename은 기존 값(`'DEFAULT'`)을 그대로 옮기므로 데이터 이관이 없다.

**추가되는 것**: 값을 넣을 경로가 없으면 4필드는 영원히 기본값이다. docs/08에 없는 `PUT /booths/{boothId}/facade`를 신설한다 — Booth Studio가 유일하게 자연스러운 편집 위치다(006은 렌더링만 한다). **신규 추가라 Breaking Change는 아니지만 FE 통보 대상**이며 docs/08 갱신을 tasks에 넣는다 (헌법 24조).

**버린 대안**: `facade_json JSONB` 한 컬럼. 항목 추가가 자유롭지만 색·URL 형식 검증이 전부 앱 로직으로 내려가고, docs/08·09 두 문서를 다시 고쳐야 한다.

---

## R-09. 오류 봉투 — 005 endpoint에만 적용한다

**결정**: `{code, message, requestId}`(docs/08 §1.3) + 검증 실패 시 `errors`·`warnings`를 얹은 봉투를 **005 신규 endpoint에만** 적용한다.

**근거**: 현재 코드에는 전역 예외 핸들러도 `ErrorCode` enum도 **없다.** 004는 `ResponseStatusException`으로 `"BOOTH_LEASE_EXPIRED: …"` 문자열을 ProblemDetail `detail`에 넣는다. FR-016의 목록을 실으려면 봉투가 필요한데, 전역으로 바꾸면 **003·004의 기존 응답 형태가 함께 바뀐다** — FE 통보 없는 Breaking Change다(헌법 24조).

**따라오는 일**: "전역 오류 봉투 통일"을 `docs/26`에 별도 항목으로 올린다. 지금 합치지 않는 이유는 미루기가 아니라 **통보 절차가 필요하기 때문**이다.

---

## R-10. `version`이 두 개다 — `schema_version`과 `version_no`를 분리해 부른다

**결정**: 계약·코드·문서에서 이름을 갈라 쓴다.

| 이름 | 의미 | 누가 올리나 |
|---|---|---|
| `schemaVersion` | Layout JSON **구조**의 버전. `scale` 추가 같은 계약 변경에서 오른다 | 3파트 합의 (헌법 21·24조) |
| `version` (=`version_no`) | 그 부스의 **공개 회차**. 공개할 때마다 1씩 오른다 | 공개 트랜잭션 |

**근거**: spec 005 §확정이 필요한 지점이 "스키마 버전과 콘텐츠 버전을 구분할 것인가"를 열어 뒀는데, **V1 스키마에는 이미 둘 다 있다**(`schema_version` 컬럼 + `version_no` 컬럼). 구분은 이미 되어 있고 **이름만 섞여 쓰이는 중**이다. docs/08 예시의 `"version": 4`는 공개 회차이고, spec 005 §Layout JSON 예시의 `"version": 2`는 문맥상 스키마 버전으로 읽힌다 — 이대로 두면 Unity가 둘을 같은 값으로 파싱한다.

**따라오는 일**: contracts에서 JSON 필드명을 `schemaVersion`·`version`으로 못 박고, spec 005 본문 예시의 `"version": 2`를 `"schemaVersion": 1`로 정정 제안 (3파트 통보 대상).
