# Research: Booth Studio Layout (FE 편집기)

`plan.md`의 Technical Context·Project Structure에서 잠정으로 남긴 결정을 해소하거나, 해소 불가한 항목은 PROVISIONAL로 명시하고 근거를 남긴다. PROVISIONAL 항목은 `docs/26_팀_결정_필요사항.md`에 등록한다(헌법 30조 — 미정 항목 임의 확정 금지).

---

## R-01. C-04 — 콘텐츠 미연결 오브젝트의 공개 차단 여부

**✅ 해소 — 기획 승인 완료 ([#45](https://github.com/kanghyunsoon/ssafesta/issues/45), 2026-08-21)**

- **Decision**: 막지 않고 경고한다(선택지 1 승인). **판정 주체는 서버다** — FR-016에 따라 BE가 Publish 검증 결과를 `errors`(차단)/`warnings`(허용)로 나눠 응답하고, 미연결은 `warnings`에 들어간다(spec.md:175). `PublishDialog`는 그 두 리스트를 **그대로 렌더링**하고, `errors`가 비어 있으면 진행 버튼을 연다. 방문자 쪽은 spec 016 FR-009와 동일하게 "오류"가 아니라 "안내"로 처리한다.
- **전제 조건 — Unity 가드**: 허용이면 미연결 오브젝트가 실제로 월드에 선다. `AiNpcInteractable`이 `configId` 검사 없이 `agentId=0`으로 대화 생성을 호출하던 구멍이 있었고(Unity `JsonUtility`가 `int` 필드 부재를 `0`으로 읽음), 리드가 `9917d4d`로 가드를 넣었다. **서버는 `configId: null`(optional), Unity는 `0`** — 표현이 다르다. FE는 서버 계약(`null`) 기준으로 짜고 `0`을 유효 ID로 다루지 않는다(`configId: 0`을 유효 ID로 발급하지 않는 것이 전제).
- **Rationale**: spec.md FR-007의 문언은 "실패 시 사유를 안내한다"이지 "차단한다"가 아니고, BE 구현도 같은 방향으로 이미 배포돼 있다. 리드도 같은 논거로 승인했다 — "임대 슬롯 축제라 자리를 먼저 확보하고 콘텐츠는 나중에 채우는 흐름을 막을 이유가 없다". `LayoutValidator.requiresConfig()` 게이트가 있어 `FURNITURE`·`DECORATION`은 경고 대상이 아니다(장식 타입에 헛경고가 쌓이는 문제는 이미 처리돼 있음). 이 결정이 향후 뒤집혀도(기획이 재논의) 서버가 `warnings`에서 `errors`로 항목을 옮기고 FE는 코드 변경 없이 따라간다 — FR-016이 존재하는 이유다.
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

**✅ 해소 — 0.25m 확정 ([#19](https://github.com/kanghyunsoon/ssafesta/issues/19), 2026-08-21)**

- **Decision**: `SNAP_METERS = 0.25`. 스냅은 미터 도메인에서 `Math.round(v / SNAP_METERS) * SNAP_METERS`로 적용한다. **서버는 검증하지 않는다** — 스냅은 편집기 UX이고 저장 값은 스냅과 무관하게 임의 실수를 받는다.
- **Rationale**: Unity·BE 양쪽이 `0.25`를 권고했다. Unity: *"6 m / 0.25 = 24칸이라 나누어떨어지고, 가장 작은 파츠(0.32 m)보다 작아서 미세 조정이 됩니다"*. BE: *"0.25 m 근거는 납득했고 FR-003 값으로 좋다고 봅니다"*. 초안의 `0.5`는 최소 파츠(0.32m)보다 커서 작은 파츠를 촘촘히 못 놓는 결함이 있었다.
- **서버 미검증인 이유** — BE가 명시적으로 제안: *"서버가 '좌표는 0.25의 배수'를 강제하면, 나중에 스냅을 0.1로 바꾸는 순간 기존 저장 데이터가 전부 무효가 된다. 스냅은 편집기 UX로 두고 서버는 영역·크기만 보는 게 안전하다."* FE도 동의 — 결정권이 전적으로 FE에 있다.
- **Alternatives**: 1m — 6×6m 부스에서 너무 성기다. 0.5m — 최소 파츠보다 커서 미세조정 불가(위 근거). 0.1m — 스냅의 의미(정렬 보조)가 사실상 사라진다. 기각.

## R-05. 부스 크기·template 화이트리스트

**✅ 해소 — 부스 6m × 6m × 2.72m, `PROJECT_EXHIBITION` 단독 확정 ([#19](https://github.com/kanghyunsoon/ssafesta/issues/19)·[#45](https://github.com/kanghyunsoon/ssafesta/issues/45), 2026-08-21)**

- **Decision**: `BOOTH_SIZE = { width: 6, depth: 6, height: 2.72 }`. 경계 검증은 이 상수에서 **도출**한다 — `|x| ≤ BOOTH_SIZE.width / 2`, `|z| ≤ BOOTH_SIZE.depth / 2`, `0 ≤ y ≤ BOOTH_SIZE.height`. 숫자를 검증식에 직접 박아 넣지 않는다. `template`은 **`PROJECT_EXHIBITION` 단독**이다 — `DEFAULT`는 제거됐고 기존 저장분은 BE V11 마이그레이션이 이관한다.
- **Rationale**: **서버 `LayoutValidator`가 이미 이 값으로 검증한다** — `|x|,|z| ≤ 3`, `0 ≤ y ≤ 2.72`(`contracts/layout-api.md` §1·§9, `data-model.md` §3). FE가 다른 값을 쓰면 저장이 서버에서 거부되므로 선택지가 아니라 계약이다. 높이는 셸 벽 패널 실측 `2.725`의 내림(#19 ②) — "6×6×6"의 세 번째 6은 x·z와 대칭으로 잡은 값이고 셸 근거가 없었다(리드, #45). 정면 트러스(z 2.85~3.15 띠, y 1.85↑)는 부스 경계 밖이라 배치 공간을 제약하지 않는다. `template` 1종은 셸 프리팹이 `BoothShell.prefab` 하나뿐이라 확정(`template → 셸 1:1`).
- **FE 실질 영향**: 높이는 편집기가 2D 톱뷰라 `y`를 노출하지 않고 `0`으로 고정 기록하므로 검증은 사실상 통과 보장이다. `template`도 선택지가 1개라 UI는 고정 라벨.
- **미리 배치된 오브젝트 세트는 반대**(리드, #45) — 12개 상한(헌법 22조)을 잠식하고, 새 부스가 `CONFIG_NOT_LINKED` 경고를 안고 태어난다(C-04가 "경고 허용"인 것과 겹치면 경고가 기본 상태가 되어 의미를 잃는다). 필요해지면 "시작 템플릿"(편집기가 초기 배치를 채워주는 UX 기능)으로 별도 처리 — 저장 계약이 아니라 서버 enum과 무관.
- **template 목록 제공**: `GET /api/v1/booth-layout-templates` 신설(#19 ④, `bearerAuth` 필요) — `{templates:[{template, footprint:{width,depth,height}, maxObjects}]}`. 편집기는 `BOOTH_SIZE`·`maxObjects`를 상수로 두지 않고 **이 응답을 소비**한다. 응답값은 BE 검증 상수에서 유도되므로 검증과 카탈로그가 어긋날 자리가 없다(#19에서 "12가 세 곳에 흩어져 있다"는 지적 반영).
- **Alternatives**: 경계값(`3`, `-3`)을 검증 코드에 직접 하드코딩 — 값이 바뀌면 산탄 수정이 된다. 기각. `DEFAULT` 유지 — "`DEFAULT`로 저장된 부스는 어느 셸인가"라는 답 없는 질문이 생긴다(리드, #45). 기각.

## R-06. 낙관적 잠금 필드 위치

**✅ 해소 — Issue #36(BE 구현 완료)으로 확정**

- **Decision**: PUT `/booths/{boothId}/layouts/draft` 요청 body에 `expectedRevision`을 포함한다. 값은 직전 `GET /draft` 응답의 `revision`을 그대로 되돌려보낸다(최초 저장은 0). 불일치 시 서버가 `409 LAYOUT_REVISION_CONFLICT`를 반환한다. 충돌 시 FE는 **자동 병합을 시도하지 않고 `GET /draft`를 다시 호출**해 최신 상태로 전체 갱신한다. `EditorState`는 마지막으로 읽은 revision을 `baseRevision`으로 보관한다.
- **🔴 revision 값을 응답에서 구조적으로 꺼내지 않는다** — 실제 구현은 `errors[0]`에 숫자 필드를 주지 않는다:
  ```
  errors: [ { rule: "CURRENT_REVISION",
              message: "서버의 현재 revision은 N입니다. 다시 불러온 뒤 저장하세요." } ]
  ```
  숫자가 **한글 메시지 문장 안에만** 있고 별도 필드가 없다. FE는 이 문자열을 파싱하지 않는다 — 파싱은 계약이 아니고, 메시지 형식이 바뀌면 조용히 깨진다. `code === 'LAYOUT_REVISION_CONFLICT'`를 확인하는 즉시 `GET /draft`를 재호출하는 것이 **유일하게 안전한 경로**다.
- **Rationale**: #36에서 BE가 "계약서 초안의 '409 본문에 최신 Draft 동봉'은 구현하지 않았다"고 통보했다 — 오류 봉투(`{code,message,requestId,errors,warnings}`)에 Draft 전체를 실을 자리가 없기 때문이다. FE는 이걸 재논의하지 않고 그대로 수용한다 — 어차피 충돌 시 `GET /draft` 한 번 더 부르는 것이 자동 병합 로직을 짜는 것보다 훨씬 단순하고, 재시도 UX도 "새로고침 후 다시 시도"로 통일된다.
- **Alternatives**: `If-Match` ETag 헤더 — REST 정석이지만 body의 `expectedRevision`과 이원화되고, BE가 이미 body 필드로 구현·배포했으므로 지금 FE가 헤더 방식을 선취할 이유가 없다. 채택하지 않음. `errors[0].message`에서 정규식으로 revision 숫자를 뽑는다 — 계약에 없는 문자열 형식에 의존하는 것이라 기각.

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

**확정 — vitest 도입**([PR #47](https://github.com/kanghyunsoon/ssafesta/pull/47), 강형순, 2026-08-22 머지)

- **Decision**: `vitest`가 devDependency로 이미 있다(`package.json`·`vite.config.ts`). 대상은 `coords.ts`·`validate.ts`·`geometry.ts`(§10-1 회전 AABB)·`passage.ts`(§10-3 통행 판정) — 부호 왕복표는 `docs/LJH/verify/block1-roundtrip.md`·`booth-studio-quickstart.md`의 실측 기대값을 그대로 테이블 테스트로 옮겼다(T025, `__tests__/unit/`).
- **Rationale**: 헌법 21조가 명시적으로 "부호 하나는 반드시 틀린다"고 경고하는 영역의 회귀 방어다. `docs/10` §18(Unit 테스트 항목)에 "Layout 변환·Validation"이 이미 명시돼 있다. Vite 프로젝트에서 vitest는 런타임 번들에 영향이 없는 devDependency다.
- **경위**: 이 결정은 원래 FE plan 단독으로 확정하지 않고 팀 승인 항목으로 남겨뒀는데, PR #47(game-studio Web Runtime 코어)이 같은 결론(vitest devDependency 1개, `<feature>/__tests__/unit/*.test.ts` 구조)으로 먼저 들어와 팀 승인이 실질적으로 이뤄졌다. T025는 이미 있는 러너·컨벤션에 얹었을 뿐 별도로 도입하지 않았다.

## R-11. facade(외부 표현) 편집 — FR-018

**✅ 범위 확정 — 2026-08-20 BE 검토로 005에 포함**

- **Decision**: facade 편집을 이 plan 범위에 넣는다. `features/studio/ui/FacadePanel.tsx` + `entities/booth/facadeApi.ts` 2파일로, **Layout 편집기의 상태 기계와 분리된 단순 폼**으로 만든다. 4필드(`themeCode`·`primaryColor`·`signText`·`logoUrl`)를 `PUT /booths/{boothId}/facade`로 저장한다.
- **Rationale**: FR-018이 2026-08-20 BE 검토에서 신설됐고, 이는 FE가 리뷰에서 올린 "외부 설정(Facade) 편집 요구사항이 005에 없다"는 지적(spec.md:211)을 BE가 채택한 결과다. 이전의 "Facade는 005 범위 제외"(2026-08-18) 판단은 폐기다. **Layout과 같은 화면 안에 두되 상태를 합치지 않는 이유**는 저장 방식이 다르기 때문이다 — facade는 Draft/Publish를 타지 않고 `booths` 컬럼에 직접·즉시 반영되며(`docs/26`), 낙관적 잠금(`expectedRevision`)도 없다. `EditorState`에 섞으면 dirty·saveStatus 의미가 두 갈래가 된다.
- **확정된 계약값**(#17 통보): `primaryColor`는 hex `#RRGGBB` 6자리로 `themeCode`와 독립(`#RGB`·`#RRGGBBAA`·이름 문자열은 전부 거부) / `logoUrl`은 업로드가 아니라 https URL 참조(≤2048자) / 만료 부스는 편집 거부(`BOOTH_LEASE_EXPIRED`, FE는 진입 시 임대 확인 + 저장 시 이중 방어) / 알파 채널 없음. `themeCode` 화이트리스트 `{DEFAULT, SSAFY_BLUE, WARM, MONO}`는 **계약 문서(`contracts/layout-api.md`)에는 없고** `docs/08_Backend_API_명세서.md:314`·구현에만 있다 — 계약 승격 전까지 출처를 `docs/08`로 명시한다.
- **팔레트 응답 형태는 합의됨, endpoint는 미구현**: `GET /api/v1/booth-facade-palette`가 계약 문서·spec·백엔드 코드 어디에도 없다(전 브랜치 grep 확인) — #17 코멘트의 합의 문장만 있다. 합의된 형태:
  ```json
  { "colors": [ { "code": "RED", "hex": "#EF4444", "label": "레드" }, ... ],
    "themeCodes": ["DEFAULT", "SSAFY_BLUE", "WARM", "MONO"] }
  ```
  12색 `{code, hex, label}`, 전역 팔레트 1개(A안, 테마와 독립). **구체 hex 12개는 "FE↔BE 구현 트랙에서" 정하기로 남았다** — FE가 착수해야 할 항목. `code`는 hex 대소문자 오차 없이 선택 상태를 추적하는 용도, `label`은 인접색(Lime/Green, Teal/Cyan) 적록색약 구분용.
- **미확정**: `booths.name`과 `facade_sign_text`의 화면상 관계는 FE 몫으로 남아 있다(`docs/26`). 폼 구현을 막지 않는다.
- **Alternatives**: 별도 spec으로 분리 — FR-018이 이미 005 FR로 신설됐으므로 spec을 거스르게 된다. 기각. `EditorState`에 facade 필드 병합 — 저장 경로·잠금 방식이 달라 상태 의미가 오염된다. 기각.

## R-12. 오류 `rule` 문자열 — 분기는 아직 안 한다

- **Decision**: FE는 `rule`로 분기하지 않고 **목록을 그대로 렌더링**한다(`message`가 한글이라 그대로 노출 가능). 분기가 필요한 값은 `CURRENT_REVISION`(→ `GET /draft` 재로드, R-06) 하나뿐이다.
- **Rationale**: `rule` 19종은 `contracts/layout-api.md`에 전부 명문화됐다(PR #57, #36 요청 반영). 다만 Bean Validation 오류 경로가 요청 필드명(`nickname` 등)을 `rule` 자리에 넣고 있어(#58에서 발견) 지금 `rule`로 분기를 열면 규칙명과 필드명이 섞여 나온다. `field` 키 분리(#58 결론)까지는 `code`로만 분기한다.
- **Alternatives**: 목록 명문화 이전처럼 rule을 전혀 참조 안 함 — 이미 사전 경고(`validate.ts`)가 rule 어휘로 서버와 대조하고 있어 과도한 보수. `rule`을 지금 도입 — #58 결론 전이라 §3 오염을 그대로 물려받는다. 기각.

---

## 미해소 항목 요약

| ID | 항목 | 해소 시점 |
|---|---|---|
| R-12 | `rule` 19종 명문화 완료(#36·PR #57) | 분기 대상으로 쓰지 않음 — Bean Validation이 필드명을 rule 자리에 섞어 보내는 결함(#58) 해소 전까지 |
| — | `themeCode` 4값이 계약 문서에 없음 | `docs/08`·구현에만 있음. 계약 문서 승격 시 반영 |
| — | facade 팔레트 12색 구체 hex 값 | "FE↔BE 구현 트랙에서" — FE 착수 필요 |
| R-11 | `booths.name`↔`facade_sign_text` 화면 관계 | FE 화면 설계 몫 — 폼 구현은 막지 않음 |
