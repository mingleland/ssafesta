# Implementation Plan: 프로젝트 전시 (009 BE분)

**Branch**: `feat/S15P21A604-110-project-api` (origin/develop 기준) | **Date**: 2026-08-28 | **Spec**: [../spec.md](../spec.md)

**Input**: `specs/009-project-exhibition/spec.md`(공동 정본) + Clarifications C-01~C-07(GitLab #110 확정) + `docs/08` §5 필드 후보 + `docs/sdd/parts/BE.md` §009

> **`BE/spec.md`는 없고 만들지도 않는다.** spec-kit이 `FEATURE_SPEC=BE/spec.md`를 조립하지만
> 명세 정본은 **상위 `../spec.md`** 하나다 (#43 — 정본 2벌은 drift). `speckit-analyze`는 기본
> 경로 대신 상위 `spec.md` + `BE/plan.md` + `BE/tasks.md`를 명시해 대조한다.

## Summary

부스의 프로젝트 전시 정보를 등록·수정하는 서버 몫. `projects` 테이블은 V1부터 있는데
**읽기·쓰기 경로가 0건**이라 소유자가 전시할 내용을 넣을 방법도 되읽을 방법도 없다.
`POST`·`PATCH`·편집자용 `GET` 세 endpoint를 열어 그 구멍을 메운다.

설계의 중심은 넷이다.

1. **부스당 1개는 DB가 지킨다** (C-01). 애플리케이션 검사만 두면 동시 `POST` 두 건이 각각
   "없음"을 보고 둘 다 만든다. `V14`로 유니크 인덱스를 걸고 `saveAndFlush()`를 `try/catch` 안에서
   호출해 제약 위반을 `409 PROJECT_ALREADY_EXISTS`로 번역한다 — `V7__booth_one_per_owner.sql`과
   `BoothLeaseService:97`이 같은 이유로 만들어진 선례다 (research R-02).
2. **016의 반복이다.** 편집자 가드 + 유효 임대 + 검증 + `booths` 아닌 자기 행에 즉시 반영.
   Draft/Publish를 타지 않는다. `BoothAccessGuard`·`findValidByBoothId`·`ApiException` 봉투를
   그대로 재사용하므로 **새로 만드는 공통 부품이 없다** — 단 하나, URL 검증기를 꺼낸다.
3. **URL 검증기를 `common/HttpUrlValidator`로 추출한다.** URL 필드가 5개라 붙여넣으면 같은
   규칙이 6벌(홈페이지 포함)이 되고 언젠가 한 벌만 고쳐진다. **`null`은 그대로 통과**시키고
   빈 문자열만 거부하며, **scheme을 host보다 먼저** 본다 — `javascript:`가 host 부재로 먼저
   걸리면 스킴 위반이라는 사유가 사용자에게 도달하지 않는다 (R-04).
4. **`PATCH`의 `null`은 삭제다** (C-06). 요청 DTO를 `record`로 두면 `{}`와
   `{"videoUrl":null}`이 구분되지 않아 FE 직렬화 실수가 등록된 값을 조용히 지운다 —
   016에서 밟은 자리다(T-97). presence 추적 클래스로 받고 `{}`는 400으로 거부한다.

**신규 테이블 0 · 신규 rule 0.** 마이그레이션은 유니크 인덱스 하나, 새 오류 어휘는 최상위
`code` 2개(`PROJECT_NOT_FOUND` · `PROJECT_ALREADY_EXISTS`)뿐이다.

방문자 노출(published 게이트)과 좋아요 수는 **S15P21A604-177**이 이어받는다. 여기서는 소유자가
자기 값을 되읽는 `GET`까지만 연다 — 그게 없으면 FE가 수정 폼을 만들 수 없다 (R-05).

결정 근거 전체: [research.md](research.md) R-01~R-10.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA, Spring Security(Resource Server), Jackson.
재사용: `BoothAccessGuard`(소유자+스태프) · `MemberPrincipal.requireMemberId(jwt, 문구)`(게스트 차단) ·
`BoothLeaseRepository.findValidByBoothId`(만료 판정) · `ApiException`/`ErrorCode`/`ApiErrorDetail.field()`(#58 봉투) ·
`java.net.URI`(형식 검증)

> ⚠️ `booth/BoothPrincipal`은 **package-private `final class`**(`BoothPrincipal.java:12`)라
> `project` 패키지에서 호출하면 컴파일되지 않는다. `common/MemberPrincipal`이 `public`이고
> 거부 문구를 인자로 받는 오버로드가 정확히 이 용도다.

**Storage**: PostgreSQL 17 — `projects` 테이블 V1부터 존재. **신규 테이블 0**, 마이그레이션은
`V14__project_one_per_booth.sql`(유니크 인덱스) 하나

**Build/Testing**: **Maven** (`backend/mvnw`, `backend\mvnw.cmd`). JUnit 5, Testcontainers(PostgreSQL),
MockMvc — 003·004·005·013a·016과 동일 패턴. 신규 통합 테스트 2개
(`ProjectApiIntegrationTest`·`ProjectConcurrencyIntegrationTest`) + 기존 `BoothHomepageApiIntegrationTest`
19개가 검증기 추출의 회귀 그물. 기준선은 착수 시 `origin/develop`에서 실측해 tasks.md에 기록

**Target Platform**: Docker Spring API (`backend/`)

**Project Type**: Web API — 기존 모놀리스에 flat 패키지 1개 + endpoint 3개 추가

**Performance Goals**: 단건 PK/FK 행 CRUD. 등록·수정은 스튜디오에서 가끔, 조회는 부스당 최대 1행 —
인덱스는 유니크 하나로 충분하고 캐시 불필요

**Constraints**: 왕복 무손실(저장 바이트 = 반환 바이트, data-model §2) · 미등록은 오류가 아니라
`null`/빈 배열 · 모든 거부에 **사유별로 다른 문장**(T-24) · 응답의 모든 키는 항상 존재(C-03)

**Scale/Scope**: 부스당 1행. endpoint 3개 + 엔티티·리포지토리·서비스·컨트롤러·예외 2개 + 공용
검증기 1개 + 마이그레이션 1개 + 테스트 2개

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 후 재확인 — **PASS** (위반 0건)*

| 조 | 요구 | 이 계획에서 |
|---|---|---|
| 1 | 영구 비즈니스 상태의 SoT는 Spring | 프로젝트 데이터는 `projects` 단일 지점. Unity·React는 조회만 |
| 4 | Booth는 데이터로 생성 — 재빌드 금지 | 프로젝트는 순수 데이터. Unity 변경 0, Layout JSON 무관 |
| 12 | 게스트는 영속 자산을 갖지 않는다 | 모든 endpoint가 `MemberPrincipal.requireMemberId`로 게스트를 `403 MEMBER_ONLY` |
| 16 | 클라이언트 주장 불신 | 편집 권한은 JWT + `BoothAccessGuard`로 서버가 판정. 값은 스킴 화이트리스트(`javascript:` 차단) · 길이 게이트 · `name` 제약 |
| 24 | 계약 변경은 합의로만 | 전부 **가산적**(endpoint 신설 + 오류 code 2개) — Breaking 0. C-05·C-06·C-07은 BE 결정이지만 **#110에서 FE에 통보 완료** |
| 25 | 텍스트 입력·외부 콘텐츠는 React | 등록 폼·링크 이동 안내·영상 임베드·새 탭 fallback 전부 FE 몫(FR-006·FR-007·FR-009). BE는 저장·검증·노출만 |
| 27 | 기준선 동결 코드 재구현 금지 | 동결 대상(`NetworkPlayer`·`ConnectionManager`·`BoothRuntime`) 미접촉. `BoothHomepageService`는 동결 대상이 아니고 **문구 보존 + 기존 테스트 19개 통과**를 조건으로 검증기만 위임 |
| 28 | 범위 통제 | 방문자 조회·좋아요는 **-177로 분리**. C-02 미결분은 후속 |
| 29 | 기록 의무 | `docs/24_작업일지.md` + 트러블 시 T-번호 |
| 30 | 미정 항목 임의 확정 금지 | C-01은 구두 합의를 **#110에서 확인받고** 기재했다 — 착수를 멈추고 물었다. C-02는 여전히 미결로 두고 **형식 검증만** 하며 소급 처리 방침까지 문서화(R-08). C-05~C-07은 BE 소관이라 결정하되 **통보**했다 |

## Project Structure

### Documentation (this feature)

```text
specs/009-project-exhibition/
├── spec.md                      # 공동 정본 (C-01~C-07). BE/spec.md 스텁 없음 (#43)
├── contracts/
│   └── project-api.md           # 공동 정본 — FE 소비. Phase 1 산출물
└── BE/
    ├── plan.md                  # 이 파일
    ├── research.md              # R-01~R-10
    ├── data-model.md            # 엔티티·불변식·검증 순서·마이그레이션
    ├── quickstart.md            # 검증 절차
    └── tasks.md                 # $speckit-tasks 산출물 (아직 없음)
```

> `contracts/`가 **최상위**인 것은 이 API를 FE가 소비하기 때문이다 — #43은 "`spec.md`와 최상위
> `contracts/`만 공동 정본"이라 했고 016도 같은 배치다. BE 내부 근거는 `BE/`에 남는다.

### Source Code (repository root)

```text
backend/src/main/java/com/example/ssafesta/
├── project/                     # 신규 flat 패키지 (game/ 과 같은 배치)
│   ├── Project.java                        # 엔티티 — booth_id 는 Long (BoothLease 스타일)
│   ├── ProjectRepository.java              # findByBoothId
│   ├── ProjectService.java                 # 쓰기 2종(등록·수정) + 조회 1종
│   ├── ProjectController.java              # endpoint 3개
│   ├── ProjectNotFoundException.java       # C-07
│   └── ProjectAlreadyExistsException.java  # C-01 중복 POST
├── common/
│   ├── HttpUrlValidator.java    # 신규 — 016 규칙 추출, null 통과, scheme 우선
│   └── ErrorCode.java           # 수정 — PROJECT_NOT_FOUND · PROJECT_ALREADY_EXISTS
└── booth/
    └── BoothHomepageService.java # 수정 — 검증기 위임. 문구는 바이트 단위 보존

backend/src/main/resources/db/migration/
└── V14__project_one_per_booth.sql          # 신규 — 유니크 인덱스 1줄

backend/src/test/java/com/example/ssafesta/project/
├── ProjectApiIntegrationTest.java          # 신규 — 계약 전부
└── ProjectConcurrencyIntegrationTest.java  # 신규 — 동시 POST
```

**Structure Decision**: 기존 Spring 모놀리스(`backend/`)에 flat 패키지 하나를 더한다.
`game/`이 2026-08-25 GitLab #48에서 flat으로 확정됐고 그 배치를 따른다. `booth/` 안에 넣지 않는
이유는 프로젝트가 부스의 하위 개념이 아니라 **부스에 붙는 별개 도메인**이고, `booth/` 패키지가
이미 40개 클래스로 커져 있어서다.

## Implementation Order

`$speckit-tasks`가 task 번호를 붙일 단위. 각 단계가 끝날 때 컴파일·테스트가 통과해야 한다.

| # | 단계 | 산출물 | 왜 이 순서 |
|:--:|---|---|---|
| 1 | 회귀 기준선 실측 | tasks.md에 클래스/테스트 수 기록 | 3단계가 기존 코드를 건드리므로, 그 전에 재야 내가 낸 실패인지 판정할 수 있다 |
| 2 | `ErrorCode` 2건 + 예외 2개 | 컴파일 통과 | 이후 전 단계가 참조한다 |
| 3 | `HttpUrlValidator` 추출 + `BoothHomepageService` 위임 | **기존 19개 테스트 통과** | 여기서 먼저 해야 문구 보존을 즉시 검증받는다. 프로젝트 코드까지 쌓아 놓고 하면 실패 원인이 섞인다 |
| 4 | `V14` 마이그레이션 | Flyway 적용 성공 | 5단계 엔티티가 제약 위에서 돈다 |
| 5 | `Project` 엔티티 + `ProjectRepository` | 컴파일 통과 | |
| 6 | `ProjectService` — presence 명령 클래스·검증·`saveAndFlush` 번역 | 단위 수준 동작 | 계약의 핵심이 전부 여기 |
| 7 | `ProjectController` endpoint 3개 | MockMvc 왕복 | |
| 8 | `ProjectApiIntegrationTest` 12 케이스 | 실패 0 | quickstart §3-1 |
| 9 | `ProjectConcurrencyIntegrationTest` | 실패 0 | 유니크 제약과 번역이 실제로 도는지 |
| 10 | 문서 — `docs/08` §5·§18, `docs/sdd/parts/BE.md` S3 제거, 작업일지 | — | 구현과 **같은 커밋** (016 선례) |

3단계를 2단계 직후에 두는 것이 이 순서의 유일한 비자명한 선택이다. 검증기 추출은 **기존 기능을
건드리는 유일한 작업**이고, 그 안전망(홈페이지 테스트 19개)이 이미 존재한다. 나중으로 미루면
새 코드의 실패와 회귀가 같은 실행에서 섞인다.

## Complexity Tracking

> Constitution Check 위반 0건 — 비워 둔다.

기록해 둘 만한 선택 둘:

| 선택 | 더 단순한 대안 | 왜 안 골랐나 |
|---|---|---|
| 유니크 인덱스 + 사전 검사 **둘 다** | 사전 검사만 | 경쟁 조건에서 두 행이 생기고 `findByBoothId`가 부스를 영구히 잠근다 (V7이 서술한 T-110 시나리오) |
| `HttpUrlValidator` 추출 | 프로젝트 쪽에 복붙 | URL 필드가 5개다. 붙여넣으면 같은 규칙이 6벌이 되고 언젠가 한 벌만 고쳐진다. 추출 비용은 파일 1개, 안전망은 이미 있는 테스트 19개 |

## 범위 밖 — 하지 않는다

| 항목 | 왜 |
|---|---|
| `project_links` 테이블 | C-05가 3칼럼으로 닫았고 정본 Key Entities도 정정됐다 (R-01) |
| URL allowlist (YouTube·GitHub 등) | D09는 형식 검증만. allowlist는 SC-002를 깬다. Jira -110 설명의 "allow 정책" 문구는 정본과 어긋난 것이고 #110에서 정정 통보 |
| 대표 이미지 업로드 | C-03 — 업로드 미지원, URL 참조. `docs/sdd/parts/BE.md`의 "S3"는 낡았다 (R-10) |
| 프로젝트를 Layout JSON에 넣기 | spec 005 계약과 무관 |
| 방문자 조회 · published 게이트 · 좋아요 수 | **S15P21A604-177** |
| `videoUrl` 제공자 제한 | **C-02 미결(기획).** 목록 확정 후 후속. 소급 삭제·숨김 안 함 (R-08) |
| `specs/009` 리뷰 서명 | C-02가 아직 열려 있다 |
| **직원 역할 게이트** (011 C-09) | `BoothAccessGuard`가 `role`을 안 읽는다. 005·016도 같다. **011 구현 때 가드 한 곳에서 일괄** — 009만 걸면 "편집자"가 endpoint마다 다른 뜻이 된다 (R-11) |
| `GET /projects/{projectId}` | 부스당 1개라 `GET /booths/{boothId}/projects`가 같은 값을 준다. 필요해지면 가산적으로 추가 (contracts §0) |
