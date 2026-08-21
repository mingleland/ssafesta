# Research: Booth Studio Layout (FE 편집기)

`plan.md`의 Technical Context·Project Structure에서 잠정으로 남긴 결정을 해소하거나, 해소 불가한 항목은 PROVISIONAL로 명시하고 근거를 남긴다. PROVISIONAL 항목은 `docs/26_팀_결정_필요사항.md`에 등록한다(헌법 30조 — 미정 항목 임의 확정 금지).

---

## R-01. C-04 — 콘텐츠 미연결 오브젝트의 공개 차단 여부

**⚠️ PROVISIONAL — 기획 승인 대기**

- **Decision**: 막지 않고 경고한다. **판정 주체는 서버다** — FR-016에 따라 BE가 Publish 검증 결과를 `errors`(차단)/`warnings`(허용)로 나눠 응답하고, 미연결은 현재 `warnings`에 들어간다(spec.md:175, BE 구현 기본값). `PublishDialog`는 그 두 리스트를 **그대로 렌더링**하고, `errors`가 비어 있으면 진행 버튼을 연다. 방문자 쪽은 spec 016 FR-009와 동일하게 "오류"가 아니라 "안내"로 처리한다.
- **Rationale**: spec.md FR-007의 문언은 "실패 시 사유를 안내한다"이지 "차단한다"가 아니고, BE 구현도 같은 방향으로 이미 배포돼 있다. **기획이 "차단"으로 뒤집히면 서버가 그 항목을 `warnings`에서 `errors`로 옮기고, FE는 코드 변경 없이 따라간다** — 이것이 FR-016이 존재하는 이유("확정되지 않은 규칙은 이 구분 안에서 자리만 옮기면 되도록 한다")다. FE가 공개 가부를 자체 판정하면 서버와 갈릴 수 있고, 갈릴 때 이기는 쪽은 서버다(헌법 16조).
- **FE 사전 검증의 위치**: 저장·공개 요청을 보내기 전에 같은 종류의 경고를 미리 띄우는 것은 **UX 보조**로만 둔다. 그 판정 기준은 `configId` 필드 유무 같은 단일 필드 검사가 아니라 **"타입별 연결 요건을 충족했는가"라는 상위 개념**으로 두고, 원본은 `data-model.md`의 `ObjectType` 판정표(연결 요건 열)다. LAPTOP처럼 연결 요건 자체가 바뀌는 경우(016에서 URL 계약으로 변경 가능)에 판정표 행 수정만으로 흡수된다. 다만 이 사전 판정은 **공개 가부의 근거가 아니다** — 최종 표시는 항상 서버 응답이다.
- **Alternatives**: FE가 자체 판정해 진행 버튼을 막는다 — 서버 응답과 갈릴 여지가 생기고, 기획 결정이 바뀔 때마다 FE 배포가 필요해진다. 기각.

## R-02. objectId 생성 주체

- **Decision**: FE가 `crypto.randomUUID()`로 생성한다.
- **Rationale**: Draft PUT은 `objects` 배열 전체 교체 방식이라, 서버가 발급하는 방식이면 아직 저장되지 않은 신규 오브젝트를 편집기 안에서 식별할 방법이 없다(선택·삭제 UX가 성립하지 않는다). `crypto.randomUUID()`는 표준 Web API라 의존성 추가가 없고, 전역 유일성이 보장돼 spec.md가 요구하는 "부스 내 유일" 조건을 자동으로 충족한다. `objectId`는 계약상 자유 string이므로(spec.md 예시의 `"screen-1"`은 하나의 예시일 뿐) UUID 채택이 스키마 변경이 아니다 — #36의 "미지 필드 거부"와도 무관하다(필드가 아니라 값 형식의 문제).
- **Alternatives**: 서버 발급 — 저장 전 식별 불가로 기각. `타입-순번`(예: `AI_AGENT-1`) — 삭제 후 재추가 시 번호 재사용 여부를 관리해야 하고, `docs/10` §8이 언급하는 "objectId 중복 검증"이 실제로 발생할 수 있는 조합이 된다. UUID를 쓰면 그 검증은 사실상 방어 코드로 강등된다. 기각.

## R-03. 픽셀 ↔ 미터 환산

**✅ 해소**

- **Decision**: 편집기 상태를 처음부터 미터 단위로 보관한다. SVG `viewBox`를 `"-width/2 -depth/2 width depth"`(미터 단위)로 잡아 좌표계를 그대로 도형 좌표로 쓴다. 화면 세로축과 Z축의 부호 반전(`docs/10` 좌표 규칙 3항 — 2D 편집기에서 아래로 내려가는 것은 `-Z`)은 `coords.ts` 한 곳에만 둔다. `PX_PER_M`은 **순수 UI 표시 배율 상수**(잠정 60px/m)로, 화면에 몇 픽셀로 그릴지만 결정하며 저장되는 JSON 값에는 전혀 관여하지 않는다 — 이 상수를 바꿔도 저장 데이터는 1비트도 달라지지 않는다는 것을 `studio.ts`의 주석과 이 문서에 명시한다.
- **Rationale**: 헌법 21조 1항("편집기 내부의 픽셀·그리드 단위는 React가 저장 직전에 환산한다")을 "환산할 지점 자체를 없애는" 방식으로 충족한다. 상태가 처음부터 미터라면 저장 시점의 환산 로직이 존재하지 않으므로, 환산 버그가 생길 표면적 자체가 사라진다. 부호 반전이 코드에 정확히 1회만 등장하므로 spec.md가 경고하는 "부호 하나는 반드시 틀린다"에 대한 구조적 대응이 된다.
- **Alternatives**: 픽셀 단위로 상태를 들고 저장 시 일괄 환산 — 줌·스냅 연산마다 환산이 산재하고 부동소수점 오차가 누적된다. 기각.

## R-04. 스냅 간격

**⚠️ PROVISIONAL — C-06(템플릿 종수) 확정에 종속**

- **Decision**: `SNAP_METERS = 0.5`. 스냅은 미터 도메인에서 `Math.round(v / SNAP_METERS) * SNAP_METERS`로 적용한다.
- **Rationale**: `BOOTH_SIZE.width/depth = 6`(R-05) 기준으로 0.5m 간격이면 축당 12칸이 생겨 배치 자유도와 정렬감이 균형을 이룬다. C-06(Unity가 셸을 6×6m 임의값으로 만들어 둔 상태 — Issue #19 진행 중)이 확정되면 `shared/config/studio.ts`의 값 하나만 바뀌고 스냅 로직·검증식은 그대로 유지된다. spec.md §BE 검토 상세 A는 **스냅을 "순수 FE 기능이며 BE 계약에 영향 0"**으로 판정했다 — 저장되는 값은 이미 환산된 미터 좌표이므로 이 상수 선택은 FE 단독 결정이고, 서버와 합의할 대상이 아니다.
- **Alternatives**: 1m — 6×6m 부스에서 너무 성기다. 0.1m — 스냅의 의미(정렬 보조)가 사실상 사라진다. 기각.

## R-05. 부스 크기

**⚠️ PROVISIONAL — C-06 확정에 종속**

- **Decision**: `BOOTH_SIZE = { width: 6, depth: 6 }` (Unity 현행 임의값 준용, Issue #31 회신에서도 같은 값 언급). 경계 검증은 이 상수에서 **도출**한다 — `|x| ≤ BOOTH_SIZE.width / 2`, `|z| ≤ BOOTH_SIZE.depth / 2`. 숫자를 검증식에 직접 박아 넣지 않는다. `template`은 서버 화이트리스트 값만 보낸다 — 현재 `DEFAULT`·`PROJECT_EXHIBITION`(spec.md:177). 편집기는 이 목록을 하드코딩하지 않고 서버가 주는 값에서 고르는 구조로 두되, 목록 제공 endpoint가 아직 없으므로 1차는 두 값을 상수로 두고 확정 시 교체한다.
- **Rationale**: 원점이 부스 바닥 중앙(헌법 21조 2항)이므로 반너비 비교 하나로 경계 검사가 끝난다. 상수에서 식을 도출하는 구조라 C-06 확정으로 부스 크기가 바뀌거나, 템플릿별로 크기가 달라지는 요구가 생겨도 `BOOTH_SIZE`를 템플릿 키로 조회하도록 확장하는 지점이 이미 하나로 모여 있다 — 지금은 그 확장을 만들지 않는다(YAGNI).
- **Alternatives**: 경계값(`3`, `-3` 등)을 검증 코드에 직접 하드코딩 — C-06 확정 시 여러 곳을 찾아 고쳐야 하는 산탄 수정이 된다. 기각.

## R-06. 낙관적 잠금 필드 위치

**✅ 해소 — Issue #36(BE 구현 완료)으로 확정**

- **Decision**: PUT `/booths/{boothId}/layouts/draft` 요청 body에 `expectedRevision`을 포함한다. 값은 직전 `GET /draft` 응답의 `revision`을 그대로 되돌려보낸다(최초 저장은 0). 불일치 시 서버가 `409 LAYOUT_REVISION_CONFLICT`를 반환하고 `errors[0]`에 서버의 현재 `revision`이 담긴다. 충돌 시 FE는 **자동 병합을 시도하지 않고 `GET /draft`를 다시 호출**해 최신 상태로 전체 갱신한다. `EditorState`는 마지막으로 읽은 revision을 `baseRevision`으로 보관한다.
- **Rationale**: #36에서 BE가 "계약서 초안의 '409 본문에 최신 Draft 동봉'은 구현하지 않았다"고 통보했다 — 오류 봉투(`{code,message,requestId,errors,warnings}`)에 Draft 전체를 실을 자리가 없기 때문이다. FE는 이걸 재논의하지 않고 그대로 수용한다 — 어차피 충돌 시 `GET /draft` 한 번 더 부르는 것이 자동 병합 로직을 짜는 것보다 훨씬 단순하고, 재시도 UX도 "새로고침 후 다시 시도"로 통일된다.
- **Alternatives**: `If-Match` ETag 헤더 — REST 정석이지만 body의 `expectedRevision`과 이원화되고, BE가 이미 body 필드로 구현·배포했으므로 지금 FE가 헤더 방식을 선취할 이유가 없다. 채택하지 않음.

## R-07. 상태관리

**✅ 해소**

- **Decision**: 추가 라이브러리 없이 `useReducer`. `StudioPage`의 로컬 reducer 하나로 충분하다(편집기는 페이지 1개라 전역 store가 불필요). 서버 상태는 `react-query`가 전담한다 — `draft` GET은 진입 시 1회만, 편집 중에는 refetch하지 않는다(`plan.md` 성능 목표 — 드래그 중 서버 요청 0건). 액션을 이산화(`ADD_OBJECT`/`MOVE_OBJECT`/`ROTATE_OBJECT`/`REMOVE_OBJECT`/`SELECT_OBJECT`/`LINK_CONTENT`/`SET_TEMPLATE` 등)해두면 P1의 undo/history는 액션 로그 재생 또는 스냅샷 스택으로 자연스럽게 확장된다 — `EditorState.history` 필드는 지금 자리만 두고 구현하지 않는다.
- **Rationale**: 신규 런타임 의존성을 만들지 않는다는 plan.md의 제약을 직접 충족한다. 오브젝트 12개 상한 규모에서 zustand 등 별도 상태 라이브러리는 과설계다.
- **Alternatives**: zustand/jotai 등 — 신규 의존성 추가라 기각. `useState` 다발 — `objects`·`dirty`·`saveStatus`·`selectedObjectId`의 정합을 개별 setState로 유지하기 어렵다. 기각.

## R-08. 2D 렌더링 기술

**✅ 해소**

- **Decision**: SVG.
- **Rationale**: 오브젝트 상한이 12개(헌법 22조)라 렌더링 성능은 애초에 논점이 아니다 — 결정 기준은 코드량이다. SVG는 오브젝트별 `<g>` 요소에 표준 DOM 이벤트(`onPointerDown` 등)로 선택·드래그가 별도 구현 없이 되고, `viewBox`가 R-03의 미터 좌표계와 직접 대응하며, 회전 표시는 `transform="rotate(deg)"` 한 줄로 끝난다. 브라우저 devtools로 요소를 직접 검사할 수 있어 quickstart.md §3(좌표 부호 검증)의 디버깅에도 유리하다.
- **Alternatives**: Canvas 2D — 히트테스트·회전·선택 하이라이트를 전부 직접 구현해야 하고, 12개 규모에서 얻는 성능 이득이 없다. 기각. `konva`/`pixi.js` — 신규 의존성이라 기각. DOM `absolute` 배치 — 회전·2D 좌표계 표현이 SVG보다 어색하다. 기각.

## R-09. Mock API

**✅ 해소**

- **Decision**: `entities/layout/api.mock.ts`에 실 API와 동일한 시그니처의 메모리 구현을 둔다. `VITE_USE_MOCK` 환경변수로 실 API와 분기한다. Draft PUT마다 `revision`을 증가시키고, `expectedRevision`이 서버 값과 다르면 `409 LAYOUT_REVISION_CONFLICT`를 반환하며, 계약에 없는 필드가 섞여 오면 `409 LAYOUT_VALIDATION_FAILED`(`rule: MALFORMED_LAYOUT`)를 반환해 **실 BE의 시맨틱을 재현**한다. Publish는 Draft 스냅샷을 복제해 `version`을 1 증가시킨다.
- **Rationale**: #36으로 실 API가 이미 배포·검증됐으므로(back PR #27, 실서버 22단계 검증), Mock의 역할은 "BE 부재를 메우는 것"에서 "로컬에서 BE 서버를 안 띄워도 개발 가능하게 하는 것"으로 바뀌었다. 그래도 만드는 이유는, revision 충돌·필드 거부 같은 실패 경로를 로컬에서 재현해야 quickstart.md §4·§5의 시나리오를 CI 없이 반복 검증할 수 있기 때문이다.
- **Alternatives**: MSW — 신규 의존성이라 기각. Mock 없이 실 BE에만 의존 — 로컬 개발 시 BE 서버 기동이 항상 전제되고, 오프라인·CI 환경에서 실패 경로 테스트가 어려워진다. 기각.

## R-10. 테스트 러너

**제안 — 팀 승인 필요**

- **Decision**: `vitest`를 devDependency로 1건 추가한다. 대상은 `coords.ts`(부호 왕복 — `docs/LJH/verify/block1-roundtrip.md`의 실측 기대값을 그대로 테이블 테스트로 옮긴다)와 `validate.ts`(검증 6종).
- **Rationale**: 헌법 21조가 명시적으로 "부호 하나는 반드시 틀린다"고 경고하는 영역의 회귀 방어다. `docs/10` §18(Unit 테스트 항목)에 "Layout 변환·Validation"이 이미 명시돼 있다. Vite 프로젝트에서 vitest는 런타임 번들에 영향이 없는 devDependency다.
- **Alternatives**: `node:test` — 런타임 의존성은 0이지만 TS strip 설정을 별도로 갖춰야 해 Vite 프로젝트 관례에서 벗어난다. 팀이 "신규 의존성 금지"를 devDependency까지 확대 해석하면 이 대안으로 전환한다.
- **참고**: plan.md 제약("신규 런타임 의존성 0")은 프로덕션 번들 기준이다. devDependency 추가 여부는 이 plan 단독으로 확정하지 않고 팀 승인 항목으로 남긴다.

## R-11. facade(외부 표현) 편집 — FR-018

**✅ 범위 확정 — 2026-08-20 BE 검토로 005에 포함**

- **Decision**: facade 편집을 이 plan 범위에 넣는다. `features/studio/ui/FacadePanel.tsx` + `entities/booth/facadeApi.ts` 2파일로, **Layout 편집기의 상태 기계와 분리된 단순 폼**으로 만든다. 4필드(`themeCode`·`primaryColor`·`signText`·`logoUrl`)를 `PUT /booths/{boothId}/facade`로 저장한다.
- **Rationale**: FR-018이 2026-08-20 BE 검토에서 신설됐고, 이는 FE가 리뷰에서 올린 "외부 설정(Facade) 편집 요구사항이 005에 없다"는 지적(spec.md:211)을 BE가 채택한 결과다. 이전의 "Facade는 005 범위 제외"(2026-08-18) 판단은 폐기다. **Layout과 같은 화면 안에 두되 상태를 합치지 않는 이유**는 저장 방식이 다르기 때문이다 — facade는 Draft/Publish를 타지 않고 `booths` 컬럼에 직접·즉시 반영되며(`docs/26`), 낙관적 잠금(`expectedRevision`)도 없다. `EditorState`에 섞으면 dirty·saveStatus 의미가 두 갈래가 된다.
- **확정된 계약값**(`docs/26`, #17 통보): `themeCode` 화이트리스트 `{DEFAULT, SSAFY_BLUE, WARM, MONO}` / `primaryColor`는 hex `#RRGGBB` 6자리로 `themeCode`와 독립 / `logoUrl`은 업로드가 아니라 https URL 참조(≤2048자) / 만료 부스는 편집 거부(`BOOTH_LEASE_EXPIRED`).
- **미확정**: 전역 팔레트 12색의 서버 소속 검증 방식은 [#17](https://github.com/kanghyunsoon/ssafesta/issues/17) 합의 진행 중이고, `booths.name`과 `facade_sign_text`의 화면상 관계는 FE 몫으로 남아 있다(`docs/26`). 둘 다 폼 구현을 막지 않는다.
- **Alternatives**: 별도 spec으로 분리 — FR-018이 이미 005 FR로 신설됐으므로 spec을 거스르게 된다. 기각. `EditorState`에 facade 필드 병합 — 저장 경로·잠금 방식이 달라 상태 의미가 오염된다. 기각.

---

## 미해소 항목 요약

| ID | 항목 | 해소 시점 |
|---|---|---|
| R-01 | C-04(콘텐츠 미연결 오브젝트 공개 차단 여부) 기획 승인 | 기획 결정 시 — **서버가 `warnings`↔`errors`로 옮기면 FE 코드 변경 없음**(FR-016) |
| R-04·R-05 | 스냅 간격·부스 크기 실값 | C-06(템플릿·부스 크기 Layout 계약 회의, Issue #19) — `shared/config/studio.ts` 값 교체만 |
| — | `ApiError.errors` 원소 타입 | Issue #17 BE 확답 대기 — `data-model.md`에는 잠정 `unknown[]`로 기록 |
| — | spec.md 예시의 `"version": 2` → `"schemaVersion": 1` 정정 | Issue #36에서 BE 제안, 3파트 합의 진행 중 |
| — | `GET /draft` 응답 형태·최초 진입 분기 | Issue #36 회신 대기 — 요청/응답 필드 차이(`revision` vs `expectedRevision`)와 Draft 미존재 시 응답 |
| R-11 | facade 팔레트 서버 검증·`booths.name` 관계 | #17 합의 / FE 화면 설계 — 폼 구현은 막지 않음 |
| R-10 | vitest devDependency 추가 | 팀 승인 |
