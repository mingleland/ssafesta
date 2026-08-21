# Implementation Plan: Booth Studio Layout (FE 편집기)

**Branch**: `front` | **Date**: 2026-08-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-booth-studio-layout/spec.md`

> **C-04 진행 상태**: 콘텐츠 미연결 오브젝트의 공개 차단 여부(spec.md:175)는 기획 승인 대기 중이다.
> 이 plan은 **BE 구현 기본값과 같은 방향("막지 않고 경고")으로 PROVISIONAL 확정하고 진행**한다(사용자 결정, 2026-08-21).
> **판정 주체는 서버다** — FR-016에 따라 BE가 검증 결과를 `errors`(차단)/`warnings`(허용)로 나눠 응답하고, C-04 확정은 이 항목을 두 리스트 사이에서 옮기는 것뿐이다.
> 따라서 FE는 서버 응답의 `warnings`를 렌더링하는 구조로 만들고 자체 사전 검증은 UX 보조로만 둔다 — 기획이 뒤집혀도 서버가 `errors`로 옮기면 FE는 코드 변경 없이 따라간다. research.md R-01 참조.
> spec 전체의 "확정"은 아니며 PROVISIONAL 항목은 `docs/26`에 등록한다(헌법 30조).

> **BE 구현 완료 반영**: [Issue #36](https://github.com/kanghyunsoon/ssafesta/issues/36)에서 BE(`strdeok`)가 spec 005를 이미 구현·머지했다(`back` PR #27, 테스트 191개, 실서버 22단계 수동 검증).
> 이 plan은 그 실구현 계약(`expectedRevision`/`version`/`schemaVersion` 3분리, 오류 봉투 5필드, 미지 필드 거부)을 기준으로 작성한다 — 초안 단계의 잠정 계약이 아니라 **이미 배포된 API**에 맞춘다.

> **spec 갱신분 반영 (2026-08-20 BE 검토, `origin/develop`)**: 초판 작성 시점의 `front` 사본에는 없던 **FR-014~FR-018**과 C-04~C-07 갱신, BE 검증 규칙 표(spec.md §BE 검토 상세 C)를 반영해 재정렬했다.
> 특히 **FR-018로 facade(외부 표현) 편집이 005 범위에 포함**됐다 — 이전의 "Facade는 005 범위 제외"(2026-08-18 판단) 전제는 폐기다. 이는 FE가 리뷰에서 올린 신규 FR 제안(spec.md:211)을 BE가 채택한 결과다.

---

## Summary

부스 소유자가 웹 2D 톱뷰 편집기에서 오브젝트(최대 12개, 헌법 22조)를 배치·연결하고 Draft로 저장·Publish하는 FE 기능이다. Layout JSON(3파트 공통 계약, 좌표 왕복 검증 통과 — `docs/LJH/verify/block1-roundtrip.md`)을 **생산하는 쪽**의 구현이다. FR-018에 따라 **부스 외부 표현(facade) 편집 화면도 같은 범위**에 포함된다.

기술 접근: SVG 기반 미터 좌표계 캔버스(픽셀 환산을 코드에서 소멸시킴), `useReducer` 단일 EditorState, `@tanstack/react-query`로 서버 상태, `expectedRevision` 낙관적 잠금(`GET /draft` 재로드로 충돌 해소, 자동 병합 없음). facade는 Draft/Publish를 타지 않고 `PUT /booths/{id}/facade`로 즉시 반영되므로 **편집기 상태 기계와 분리된 단순 폼**이다.

지배 제약: **신규 런타임 의존성 0** + Layout JSON 스키마 동결(FR-013, 헌법 24조 — 미지 필드는 BE가 `409 LAYOUT_VALIDATION_FAILED`로 거부) + BE는 이미 배포돼 있어 Mock은 로컬 개발 보조로 강등 + **공개 가부 판정은 서버 몫**(FR-016).

## Technical Context

**Language/Version**: TypeScript ~6.0 / React 19.2 (`festa-frontend`, Vite 8)

**Primary Dependencies**: `@tanstack/react-query` 5.101, `react-router-dom` 7.18 — **신규 런타임 의존성 없음**

**Storage**: 없음. 서버가 유일한 저장소다(헌법 1조). `localStorage` 미사용, Autosave는 P1 이연

**Testing**: `oxlint` + 좌표 변환·검증 함수 단위 테스트. 러너는 research.md R-10(vitest 제안, 팀 승인 항목)

**Target Platform**: 데스크톱 크롬 브라우저. 모바일 Studio는 P1 이연

**Project Type**: web SPA — `festa-frontend/` 단일 프로젝트

**Performance Goals**: 드래그 조작 중 서버 요청 0건(`docs/10` §17). 오브젝트 이동은 로컬 상태만 갱신, 저장 시점에만 통신

**Constraints**: Layout JSON 필드 추가·변경 금지(FR-013) — BE는 계약 외 필드를 전부 거부한다(#36). FE 검증은 UX용 빠른 검증이고 **공개 가부의 최종 판정은 서버**다(헌법 16조, FR-016). `template`은 서버 화이트리스트에 있는 값만 허용(현재 `DEFAULT`·`PROJECT_EXHIBITION`)

**Scale/Scope**: 페이지 1개(`/app/studio/:boothId`) + 컴포넌트 6~7개(facade 폼 포함) + 순수 모듈 2개(`coords.ts`, `validate.ts`), Object Type 10종, facade 4필드

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 설계 후 재확인.*

| 조항 | 게이트 | 판정 |
|---|---|---|
| 4 — Booth는 데이터로 생성 | 편집기 산출물은 순수 데이터(종류·위치·회전·연결 ID)뿐. 3D 자산·에셋 참조를 Layout JSON에 넣지 않는다(FR-009) | ✅ 통과 |
| 13 — 토큰 4계층 | Studio는 순수 React 화면. `shared/api/client.ts`의 Access Token만 사용, Unity·connection token 무관 | ✅ 통과 |
| 16 — 클라이언트 불신 | FE 검증 6종은 빠른 UX 검증이며 서버 최종 검증을 대체하지 않는다. **공개 가부는 서버가 `errors`/`warnings`로 판정**하고 FE는 그 결과를 렌더링한다(FR-016) — FE 판정이 서버와 갈리면 서버가 이긴다. Route Guard도 서버 인가(403)를 대체하지 않는다 | ✅ 통과 |
| 21 — 좌표 규칙 | 미터/부스 바닥 중앙 원점/+Z 정면·+X 오른쪽/rotationY degree 0=+Z 시계방향+ 를 `coords.ts` 단일 모듈에 고정. 왕복 검증 2026-08-20 통과 | ✅ 통과 |
| 22 — 오브젝트 상한 12개 | 팔레트 12개 도달 시 추가 비활성 + publish 전 재검증 이중 적용 | ✅ 통과 |
| 24 — 계약 변경 절차 | Layout JSON 스키마는 무변경. 유일한 델타(`schemaVersion` vs `version` 표기 정정)는 spec 예시 오기 수정이며 BE가 #36에서 제안, 3파트 합의 진행 중 | ⚠️ 조건부 통과 — 합의 확정 시 spec.md 예시 갱신 |
| 25 — 텍스트 입력은 React | 속성 편집·콘텐츠 연결 전부 React. Unity는 트리거만 | ✅ 통과 |
| 30 — 임의 확정 금지 | C-04(공개 차단 여부)·C-06 종속값(스냅 간격·부스 크기)을 전부 PROVISIONAL 표기하고 `docs/26`에 등록. 코드에서는 설정값 1곳 격리 | ⚠️ 조건부 통과 — research.md 미해소 항목 표 참조 |

**Post-Design 재확인 (Phase 1 이후)**: `data-model.md` 작성 후에도 위 판정 변동 없음. C-04는 **서버가 `warnings`로 내려주는 것을 FE가 렌더링**하는 구조라 기획 결정이 뒤집혀도 FE 코드 변경이 없다. `ObjectType` 판정표(연결 요건 열)는 저장 전 사전 경고를 띄우기 위한 **UX 보조**이며 공개 가부의 근거가 아니다 — 24·30조 조건부 통과 상태를 코드 구조로 최소화했다는 뜻이지 완전 해소는 아니다.

## Project Structure

### Documentation (this feature)

```text
specs/005-booth-studio-layout/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 미확정 항목 해소/기록
├── data-model.md         # Phase 1 — 엔티티 정의
├── quickstart.md         # Phase 1 — 검증 시나리오
├── contracts/            # 작성하지 않음 — 사유는 아래
└── tasks.md              # Phase 2 — $speckit-tasks 산출물 (아직 없음)
```

**`contracts/`를 만들지 않는 이유**: Layout JSON 계약은 이미 `spec.md`(§공통 계약 기준, 원본) · `docs/08_Backend_API_명세서.md` §4(API) · `docs/10_Frontend_설계서.md` §7(TS 타입) 세 곳에 있다. 헌법 24조상 이 계약의 변경은 3파트 합의 사항인데, `contracts/`에 네 번째 사본을 두면 drift 지점만 늘어난다. 013이 `contracts/`를 만든 건 그 세 문서 어디에도 없던 신규 계약(NGO struct·Bridge)이었기 때문이고, 005는 반대로 계약이 이미 3벌 있는 상황이다.

### Source Code (repository root)

```text
festa-frontend/src/
├── app/router/index.tsx                   [수정] /app/studio/:boothId 라우트 + Owner/Staff Guard
├── pages/studio/StudioPage.tsx            [신규] 조립 지점 — query + reducer 결선
├── features/studio/
│   ├── model/
│   │   ├── editorReducer.ts               [신규] EditorState + 이산 액션
│   │   └── useLayoutMutations.ts          [신규] draft PUT / publish POST + 409 처리
│   ├── ui/
│   │   ├── EditorCanvas.tsx               [신규] SVG 톱뷰(viewBox=미터)
│   │   ├── ObjectPalette.tsx              [신규] Object Type 10종, 12개 도달 시 비활성
│   │   ├── PropertiesPanel.tsx            [신규] 위치·회전·콘텐츠 연결 편집
│   │   ├── PublishDialog.tsx              [신규] 서버 errors/warnings 렌더링 — C-04 표시 지점
│   │   └── FacadePanel.tsx                [신규] FR-018 외부 표현 4필드 폼(Draft 없이 즉시 반영)
│   └── lib/
│       ├── coords.ts                      [신규] 화면축↔Z 부호 반전 단일 지점
│       └── validate.ts                    [신규] FE 사전 검증(순수 함수, ObjectType 판정표 참조 — UX 보조)
├── entities/layout/
│   ├── types.ts                           [신규] docs/10 §7 타입 전사(정확 일치 필수 — 미지 필드 거부)
│   ├── objectTypes.ts                     [신규] Object Type 10종 판정표(연결 요건 열 — 사전 경고용)
│   ├── api.ts                             [신규] layouts 4개 endpoint 클라이언트
│   └── api.mock.ts                        [신규] 로컬 개발용 메모리 mock(실 BE 시맨틱 재현)
├── entities/booth/facadeApi.ts            [신규] PUT /booths/{id}/facade — FR-018
└── shared/config/studio.ts                [신규] BOOTH_SIZE·SNAP_METERS·PX_PER_M — PROVISIONAL 설정값
```

**Structure Decision**: `entities/layout`(계약 타입·API)과 `features/studio`(편집기 UI·상태)를 분리한다. Layout 타입은 spec 006 미리보기 등 다른 소비자가 생길 수 있고, 계약 타입 파일 하나가 3파트 계약의 FE 측 사본임을 구조로 드러내기 위해서다.

## Complexity Tracking

해당 없음 — 위반 없음.
