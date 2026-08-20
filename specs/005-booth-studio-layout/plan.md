# Implementation Plan: Booth Studio / Layout 계약

**Branch**: `feature/booth-studio-layout` | **Date**: 2026-08-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-booth-studio-layout/spec.md`

## Summary

부스 배치의 **작업본(Draft) / 공개본(Published) 분리**를 구현한다. 저장·검증·버전의 권위는 Spring이고, React는 편집기, Unity는 소비자다(헌법 1·21조).

설계의 중심은 네 가지다.

1. **BE는 Layout JSON의 값을 변형하지 않는다.** 좌표 규칙(헌법 21조)은 React가 환산해 보내고 Unity가 그대로 읽는다. BE가 반올림·정규화·키 재정렬을 하면 SC-004(React 미리보기와 Unity 월드 일치)가 조용히 깨진다. 저장은 `JSONB`이되 **왕복 무손실**을 테스트로 못 박는다.
2. **공개는 복사다.** Draft를 새 `version_no`로 복사하고 `booths.published_layout_version` 포인터를 옮기는 것이 하나의 트랜잭션이다. 포인터가 있어야 FR-011/FR-017(재임대 시 자동 공개 금지)이 표현된다 — `MAX(version_no)` 유도로는 이전 공개본이 되살아난다.
3. **만료 판정 술어를 복제하지 않는다.** 공개본 조회(FR-015)는 004의 `BoothLeaseRepository` 유효성 쿼리를 그대로 쓴다. 004에서 이미 기록한 위험이다 — 술어가 여러 곳에서 하중을 받는데 한 곳만 빠뜨리면 조용히 틀린다.
4. **동시 편집은 낙관적 잠금이다.** V1의 `booth_layout_drafts.revision`이 그 수단이다. 비관적 잠금은 "잠근 채 브라우저를 닫으면 아무도 못 고친다"는 해제 문제를 새로 만든다.

facade는 **docs/08·09 설계 문서대로** 4필드로 간다 (2026-08-20 결정). V1의 단일 `facade_code`를 4컬럼으로 정렬한다.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA, Spring Security (Resource Server), Flyway, Jackson. **004 `BoothLeaseRepository`·`BoothRepository`** (내부 재사용)

**Storage**: PostgreSQL 17 — `booth_layout_drafts` · `booth_layout_published_versions` 는 V1에 존재. 신규 마이그레이션은 **V8**(공개본 포인터) · **V9**(facade 4컬럼) 둘뿐

**Testing**: JUnit 5, Testcontainers(PostgreSQL·Redis), MockMvc. 003·004와 동일

**Target Platform**: Docker Spring API

**Project Type**: Web API (`backend/`)

**Performance Goals**: 부스당 오브젝트 12개, `layout_json` 수 KB. Published 조회는 Unity가 부스 진입마다 호출하는 경로 — 단건 인덱스 조회로 끝나야 한다

**Constraints**: 작업본이 방문자에게 노출된 사례 0건(SC-003) · 저장→조회 왕복에서 값이 바뀐 사례 0건(SC-004) · 남의 저장을 조용히 덮어쓴 사례 0건(FR-014)

**Scale/Scope**: 부스 7개(슬롯 수) / 부스당 Draft 1행 + 공개 버전 N행

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 조 | 요구 | 이 계획에서 |
|---|---|---|
| 1 | Layout의 Source of Truth는 Spring | Draft·Published 전부 Spring/PostgreSQL. Unity·React는 복제 저장하지 않는다 |
| 4 | Booth는 데이터로 생성 | 오브젝트 추가에 Unity 재빌드가 필요 없다. `type` 화이트리스트는 **BE 설정값**이라 Unity 릴리스와 분리된다 |
| 16 | 클라이언트 주장 불신 | `configId`가 **그 부스 소유 콘텐츠**인지 서버가 확인한다. 요청의 boothId·소유자 주장은 JWT subject로만 유도 |
| 21 | Layout 좌표 규칙 | **BE는 값을 변형하지 않는다.** 범위 검증만 하고 반올림·정규화하지 않는다. 왕복 무손실 테스트로 고정 |
| 22 | 오브젝트 12개 상한 | 공개 시점뿐 아니라 **Draft 저장 시점에도** 건다 |
| 24 | 계약 변경은 합의로만 | docs/08에 없는 `PUT /booths/{id}/facade`를 신설한다 — **추가**이지 Breaking Change는 아니나 FE 통보 대상. docs/08 갱신을 tasks에 포함 |
| 29 | 기록 의무 | `docs/HDD/작업일지.md` · `트러블슈팅.md` |
| 30 | 미정 항목 임의 확정 금지 | C-03(scale)·C-04(미연결)·C-06(템플릿)을 확정하지 않는다. scale은 `schema_version`으로 후행 확장, C-04는 warning 자리에 두어 확정 시 error로 **옮기기만** 하면 되게 한다 |

**결과: PASS** (§Complexity Tracking에 기록한 오류 봉투 항목 1건 제외 — 위반이 아니라 범위 경계다)

## Project Structure

### Documentation (this feature)

```text
specs/005-booth-studio-layout/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정과 근거
├── data-model.md        # Phase 1 — 엔티티·제약·불변식
├── quickstart.md        # Phase 1 — 검증 절차
├── contracts/
│   └── layout-api.md    # REST 계약 (FE·Unity 소비)
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
backend/src/main/java/com/example/ssafesta/booth/
├── BoothLayoutDraft.java              # 작업본 — PK=booth_id, revision 보유
├── BoothLayoutDraftRepository.java
├── BoothLayoutPublishedVersion.java   # 공개본 — (booth_id, version_no) UNIQUE
├── BoothLayoutPublishedVersionRepository.java
├── LayoutJson.java                    # JSONB 저장 문자열 + 파싱 결과 (무손실 보관)
├── LayoutObjectType.java              # canonical 10종 화이트리스트
├── LayoutTemplate.java                # DEFAULT / PROJECT_EXHIBITION (C-06 확정 시 확장)
├── LayoutValidator.java               # errors / warnings 분리 (FR-016)
├── LayoutValidationResult.java
├── BoothLayoutService.java            # 저장 · 공개 — 트랜잭션 경계
├── BoothLayoutQueryService.java       # Draft 조회 · Published 조회(만료 술어 재사용)
├── BoothLayoutController.java         # /booths/{id}/layouts/{draft,publish,published}
├── BoothFacadeService.java            # facade 조회·수정
├── BoothFacadeController.java         # PUT /booths/{id}/facade
├── BoothEditorGuard.java              # owner + booth_staffs 권한 판정 (FR-012)
├── BoothStaff.java / BoothStaffRepository.java   # 읽기 전용 — 초대 흐름은 011
├── LayoutRevisionConflictException.java
├── LayoutValidationFailedException.java
└── BoothEditorForbiddenException.java

backend/src/main/java/com/example/ssafesta/booth/api/
└── BoothLayoutErrorHandler.java       # 005 endpoint 한정 {code,message,requestId,errors,warnings}

backend/src/main/resources/db/migration/
├── V8__booth_published_layout_version.sql   # booths.published_layout_version (NULL 허용)
└── V9__booth_facade_fields.sql              # facade_code → 4컬럼 (docs/09 정렬)

backend/src/test/java/com/example/ssafesta/booth/
├── BoothLayoutServiceIntegrationTest.java       # 저장·공개·포인터 전이
├── BoothLayoutRoundTripIntegrationTest.java     # 왕복 무손실 (SC-004)
├── BoothLayoutValidationTest.java               # errors/warnings 분류
├── BoothLayoutConcurrencyIntegrationTest.java   # revision 충돌 (FR-014)
├── BoothLayoutApiIntegrationTest.java           # 권한·만료·404/409
└── BoothLayoutReleaseIntegrationTest.java       # 재임대 시 포인터 해제 (FR-017)

backend/bruno/05-booth-layout/                   # 003·004와 같은 형식
```

**Structure Decision**: 004가 만든 `booth` 패키지에 그대로 얹는다. Layout은 Booth의 일부이고, 만료 술어(`BoothLeaseRepository`)와 소유 판정(`Booth.isOwnedBy`)을 재사용해야 하므로 패키지를 가르면 그 둘을 public으로 열어야 한다. 오류 응답만 `booth/api/`로 분리해 **005 한정 봉투**임을 구조로 드러낸다.

## Complexity Tracking

| 항목 | 왜 필요한가 | 더 단순한 대안을 버린 이유 |
|---|---|---|
| 005 전용 오류 봉투 (`booth/api/BoothLayoutErrorHandler`) | FR-016이 `errors`·`warnings` **목록**을 요구한다. 현재 코드는 `ResponseStatusException`으로 `"CODE: 메시지"` 문자열을 ProblemDetail `detail`에 넣을 뿐이라 목록을 실을 자리가 없다 | ① 문자열에 계속 욱여넣기 → FE가 문자열을 파싱해야 한다 ② **전역 봉투로 통일** → 003·004의 기존 응답 형태가 바뀐다. FE 통보 없는 Breaking Change라 헌법 24조에 걸린다. 전역 통일은 별도 건으로 `docs/26`에 올린다 |
| `LayoutJson` 원문 보관 | 왕복 무손실(SC-004). 파싱한 객체를 다시 직렬화하면 키 순서·소수 표기가 바뀔 수 있다 | 파싱 후 재직렬화 → Unity·React가 받는 값이 저장한 값과 문자 단위로 달라진다. 좌표 규칙 왕복 검증(spec ④칸)이 무엇을 검증한 것인지 모호해진다 |
