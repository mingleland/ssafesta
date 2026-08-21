# Tasks: Booth Studio Layout (FE 편집기)

**Input**: `specs/005-booth-studio-layout/` — [../spec.md](../spec.md)(공동 정본) · [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [../contracts/layout-api.md](../contracts/layout-api.md)(BE 작성 정본, §9·§10은 `feature/booth-layout-geometry` 병합 대기)

**Tests**: vitest는 R-10 팀 승인 대기 — 승인 전까지 테스트 태스크는 Polish에 조건부로만 둔다. 검증은 quickstart.md 시나리오 수동 실행 + `tsc -b`.

**Organization**: US1~US3은 spec.md의 User Story, US4는 FR-018(facade — spec 리뷰에서 추가된 요구사항, BE도 US4로 분류).

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

`festa-frontend/src/` 하위. 구조 근거는 plan.md §Source Code.

---

## Phase 1: Setup — 계약 전사

**Purpose**: 서버 계약을 TS 타입으로 옮긴다. 여기가 틀리면 전부 틀린다(미지 필드 = 저장 거부).

- [x] T001 [P] `shared/config/studio.ts` — `SNAP_METERS=0.25`(확정, #19)·`PX_PER_M=60`(FE 단독 표시 상수)·`BOOTH_SIZE_FALLBACK={width:6,depth:6,height:2.72}`(서버 응답 도착 전 기본값). PX_PER_M이 직렬화와 무관함을 주석으로 명시
- [x] T002 [P] `entities/layout/types.ts` — contracts §1 전사: `LayoutObject{objectId,type,position{x,y,z},rotationY,configId?,assetCode?}` / `DraftGetResponse`(revision·updatedByUserId·publishedVersion 포함, 204 가능) / `DraftPutRequest`(expectedRevision 필수) / `DraftPutResponse`(warnings 포함, updatedByUserId·publishedVersion 없음 — **GET/PUT 분리**, data-model.md 표) / `PublishResponse` / `TemplateCatalog`(§9)
- [x] T003 [P] `entities/layout/objectTypes.ts` — ObjectType 10종 union + 판정표(분류·연결 요건·사전 경고 대상, data-model.md) + §10-1 로컬 AABB 10행(min/max 비대칭 그대로 — size로 뭉개지 말 것)

**Checkpoint**: `tsc -b` 통과. 타입만으로 커밋 가능.

---

## Phase 2: Foundational — API·좌표·라우트

**Purpose**: 모든 US가 딛는 기반. US 착수 전 완료 필수.

- [x] T004 `entities/layout/api.ts` — `getDraft`(204→null)·`putDraft`·`publish`·`getPublished`·`getTemplates`(§9, bearerAuth) 5함수. `client.ts`의 `api<T>()` 재사용, 204는 client가 이미 undefined 반환
- [x] T005 `entities/layout/api.mock.ts` — 메모리 mock, `VITE_USE_MOCK` 분기. revision 증가·`expectedRevision` 불일치 409(`LAYOUT_REVISION_CONFLICT`)·미지 필드 409(`MALFORMED_LAYOUT`)·12개 초과 409·publish 시 warnings(`CONFIG_NOT_LINKED`) 재현. 오류는 `ApiError` 5필드 봉투 형태로 throw
- [x] T006 [P] `features/studio/lib/coords.ts` — 화면 y↔`-Z` 부호 반전 유일 지점(`svgY = -z`)·스냅(`Math.round(v/SNAP_METERS)*SNAP_METERS`)·경계 클램프(`|x|≤width/2, |z|≤depth/2` — 상수 도출, 하드코딩 금지)
- [x] T007 `app/router/index.tsx`에 `/app/studio/:boothId` 라우트 추가 + `pages/studio/StudioPage.tsx` 골격(boothId 파싱, draft query 결선만)

**Checkpoint**: mock으로 `getDraft` 호출이 화면에 raw 표시되면 통과.

---

## Phase 3: US1 — 부스를 꾸며서 공개한다 (P0) 🎯 MVP

**Goal**: 배치→저장→새로고침 복원→공개. spec Independent Test 그대로.

**Independent Test**: quickstart §2(SC-001) — 3개 배치→저장→새로고침 복원→공개→published에 3개.

- [x] T008 [US1] `features/studio/model/editorReducer.ts` — `EditorState{boothId,template,objects,selectedObjectId,dirty,saveStatus,baseRevision}` + 이산 액션(`ADD_OBJECT`/`MOVE_OBJECT`/`ROTATE_OBJECT`/`REMOVE_OBJECT`/`SELECT_OBJECT`/`LOAD_DRAFT`). objectId는 `crypto.randomUUID()`(R-02)
- [x] T009 [US1] `features/studio/ui/EditorCanvas.tsx` — SVG 톱뷰, `viewBox="-w/2 -d/2 w d"`(미터), 오브젝트 `<g>` 렌더+`transform=rotate()`, pointer 드래그(이동 중 서버 요청 0건)+스냅+클램프(T006 사용), 클릭 선택
- [x] T010 [P] [US1] `features/studio/ui/ObjectPalette.tsx` — 10종 목록, `maxObjects`(서버 §9 응답) 도달 시 전체 비활성
- [x] T011 [US1] `features/studio/model/useLayoutMutations.ts` — draft PUT(`expectedRevision` echo, 성공 시 `baseRevision` 갱신)·publish POST. react-query mutation, `saveStatus` 전이(idle→dirty→saving→saved/error/conflict)
- [x] T012 [US1] `features/studio/ui/PublishDialog.tsx` — publish 응답의 `errors`(진행 차단)/`warnings`(허용) 두 리스트 렌더링. `isApiError` 가드 사용. C-04 표시 지점 — FE 자체 판정 없음
- [x] T013 [US1] `StudioPage.tsx` 조립 — draft 로드(`LOAD_DRAFT`, 204면 빈 배치+`expectedRevision:0`)·저장/공개 버튼·dirty 표시

**Checkpoint**: quickstart §2·§3(좌표 부호: 화면 아래 드래그→`z<0`, 오른쪽→`x>0`, rotationY 90→+X) 통과 — **MVP 완성**.

---

## Phase 4: US2 — 기능 오브젝트에 내용 연결 (P0)

**Goal**: configId 연결 + 미연결 사전 경고.

**Independent Test**: AI 오브젝트 배치→configId 입력→저장 JSON에 포함. 미연결 상태로 공개 시도→warnings에 표시 + 진행 가능(C-04 확정값).

- [x] T014 [US2] `features/studio/ui/PropertiesPanel.tsx` — 선택 오브젝트의 위치(x·z 숫자 입력, 비숫자 입력단 차단)·회전(0≤r<360 정규화)·configId 연결(기능형만)·assetCode(장식형만) 편집. `LINK_CONTENT` 액션 추가
- [x] T015 [P] [US2] `features/studio/lib/validate.ts` — 사전 경고(순수 함수): 판정표 기반 연결 요건 미충족 목록·objectId 중복·12개 초과. **UX 보조 — 최종 판정은 서버**(헌법 16조) 주석 명시
- [x] T016 [US2] PublishDialog에 사전 경고 통합 — 공개 요청 전 T015 결과 표시, 요청 후 서버 warnings로 교체

**Checkpoint**: quickstart §6(C-04 — configId 없는 AI_AGENT 공개 시 warnings 표시+진행 가능, FURNITURE는 목록에 없음) 통과.

---

## Phase 5: US3 — 실수로 공개되지 않게 한다 (P1)

**Goal**: 충돌·검증 실패·만료가 조용히 지나가지 않게.

**Independent Test**: quickstart §5(탭 2개 revision 충돌) + §4(13개 주입 시 저장 거부).

- [x] T017 [US3] 409 `LAYOUT_REVISION_CONFLICT` UX — conflict 상태에서 "다른 편집자가 저장했습니다" 안내+재로드 버튼(`GET /draft` 재호출→`LOAD_DRAFT`). **revision 파싱 금지**(errors[0].message는 한글 문장뿐 — data-model.md). MVP(T011) 단계에서 이미 구현됨, 이번엔 재확인만
- [x] T018 [P] [US3] `LAYOUT_VALIDATION_FAILED` 시 errors 목록 렌더(`objectId` 있으면 해당 오브젝트 선택·강조)·`BOOTH_LEASE_EXPIRED` 시 편집 차단 안내
- [x] T019 [US3] 재임대 진입 — draft만 있고 published 없는 상태(`publishedVersion: null`)에서 "비공개" 표시(FR-011·FR-017은 서버 몫, FE는 표시만)

**Checkpoint**: quickstart §4·§5 통과.

---

## Phase 6: US4 — facade 외부 표현 편집 (FR-018)

**Goal**: 4필드 즉시 반영 폼 — Draft/Publish·낙관적 잠금과 무관.

**Independent Test**: quickstart §7 — themeCode·primaryColor 변경 저장 시 layout revision 불변, `#FFF`(3자리) 거부.

- [ ] T020 [P] [US4] `entities/booth/facadeApi.ts` — `PUT /booths/{id}/facade`(4필드 전부 nullable)·`GET /booths/{id}`에서 facade 초기값. 팔레트 endpoint는 미구현(#17)이라 themeCode 4값(`docs/08` 출처)을 상수로
- [ ] T021 [US4] `features/studio/ui/FacadePanel.tsx` — 4필드 폼(themeCode 선택·primaryColor `#RRGGBB` 입력단 검증·signText ≤60자·logoUrl https ≤2048자), 만료 부스 이중 방어(진입 시 확인+`BOOTH_LEASE_EXPIRED` 처리). EditorState와 상태 분리(R-11)

**Checkpoint**: quickstart §7 통과.

---

## Phase 7: Polish — 기하 정밀 검증·마무리

- [ ] T022 `features/studio/lib/validate.ts`에 §10-1 회전 AABB 사전 검증 추가 — 네 모서리 회전(`x'=x·cos+z·sin, z'=−x·sin+z·cos`) 후 AABB 재계산, 오차 1e-9. objectTypes.ts의 bounds 사용. 드래그 중 실물 이탈 표시
- [ ] T023 `features/studio/lib/passage.ts` — §10-3 통행 판정 실시간 경고(래스터 0.05m·120×120·침식 0.22m·flood fill 4방향·관람 띠 0.7m·50%·고립 1㎡). **서버와 같은 답**이 계약 전제(#19). 12개 규모라 배치 변경 시 재계산으로 충분
- [ ] T024 quickstart §1~§7 전 시나리오 수동 실행 + 결과를 `docs/LJH/verify/`에 기록
- [ ] T025 (R-10 승인 시) vitest 추가 — coords 부호 왕복표(`block1-roundtrip.md` 실측값)·validate·회전 AABB·passage 테이블 테스트

---

## Dependencies

```text
Phase 1 (T001-T003) → Phase 2 (T004-T007) → US1 (T008-T013) → US2 (T014-T016) → US3 (T017-T019)
                                                                └→ US4 (T020-T021, US2·US3와 독립 — US1만 선행)
US1~US4 완료 → Polish (T022-T025)
```

- US4는 US1 이후 언제든 병렬 가능(편집기 상태와 분리된 폼)
- T022·T023은 계약(§10)이 `feature/booth-layout-geometry` 병합 전이지만 값이 이슈에서 3파트 확정이라 착수 가능

## Implementation Strategy

**MVP = Phase 1~3 (T001~T013)** — "Unity 없이 3D 공간을 만든다"는 정체성 그 자체(spec US1). 여기까지 mock으로 완결 검증 후 실 BE 연결. US2가 그다음(P0), US3·US4·Polish 순.
