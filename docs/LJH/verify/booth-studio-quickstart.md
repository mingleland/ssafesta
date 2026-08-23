# spec 005 Booth Studio — quickstart §1~§7 전 시나리오 인수검사 (검증 키트)

> spec 005 FE 구현(US1~US4·T022-T023·T026)이 완료된 시점에서 `FE/quickstart.md`의 전 시나리오를
> 브라우저(mock)로 재실행한 최종 인수검사 기록이다. T024.

## 목적

`FE/plan.md`·`FE/data-model.md`의 설계가 구현 최종본에서 실제로 동작하는지, spec 005 FE 착수 후
누적된 모든 변경(US1~US4, T022~T023 §10 기하 계약, T026 결함 10건 수정)이 서로 간섭 없이
함께 작동하는지 한 번에 확인한다.

**관련**: `FE/tasks.md` T024, `FE/quickstart.md`, [PR #47](https://github.com/kanghyunsoon/ssafesta/pull/47) 이후 vitest 도입

---

## 실행 환경

- `festa-frontend`, `VITE_USE_MOCK=true`, `npm run dev`
- boothId `42`(오염 없는 신규 부스) — §5·§6은 진행 중 `boothId 42`에서 이어서 실행
- 실행 방식: Browser MCP(`javascript_tool`)로 React 컨트롤드 입력에 네이티브 setter + `input`/`focusout` 이벤트를 디스패치, 버튼은 DOM 텍스트로 특정해 `.click()`. 캔버스 드래그는 `left_click_drag`(§3) 또는 합성 PointerEvent(§4 selection).

## 판정 기준

각 항목은 quickstart 원문의 확인 문구를 그대로 판정 기준으로 쓴다. 실측값은 `get_page_text`·
`sessionStorage`(mock 저장소) 직접 조회로 교차 확인했다.

---

## 결과 (2026-08-22 실행)

### §2. SC-001 — 기본 왕복 — ✅ 5/5

| # | 확인 | 실측 |
|---|---|---|
| 1 | AI_AGENT·VIDEO_SCREEN·FURNITURE 배치 | 팔레트 클릭 3회 → `오브젝트 3/12` |
| 2 | 드래그 시 `dirty` 전환 | 드래그 직후 `저장 상태: dirty` |
| 3 | 저장 → `saving`→`saved` | 저장 클릭 후 `저장 상태: idle`(`saved`를 거쳐 안정 상태로) |
| 4 | 새로고침 → 배치 복원 | 리로드 후 `오브젝트 3/12` 유지 |
| 5 | Publish → published 3개 | `sessionStorage` 조회 — `draftCount:3, publishedCount:3, publishedVersion:1` |

### §3. 좌표 부호 검증 (헌법 21조) — ✅ 3/3

| # | 확인 | 실측 |
|---|---|---|
| 1 | 아래로 드래그 → `z<0` | 드래그 후 PropertiesPanel `z=-1.5` |
| 2 | 오른쪽으로 드래그 → `x>0` | 드래그 후 `x=1.75` |
| 3 | `rotationY=90` → `+X` 방향 표시 | 회전 90 입력 → VIDEO_SCREEN 실물이 세로로 눕고 정면 표시선이 화면 오른쪽을 가리킴(스크린샷 확인) |

`block1-roundtrip.md`의 Unity 실측표와 부호 일치 — 별도 재검증 불요(좌표 변환 로직 T026 이후 무변경).

### §4. 12개 상한 — ✅ 3/3

| # | 확인 | 실측 |
|---|---|---|
| 1 | 12개에서 팔레트 전체 비활성화 | 팔레트 버튼 10종 전부 `disabled=true`, `svg g` 카운트로 12개 실제 렌더 확인(표시 텍스트는 React 배치 렌더 전 읽으면 stale — svg 카운트로 교차 검증) |
| 2 | 13개 Draft 직접 주입 → Publish 차단 + 사유 | `sessionStorage`에 13개 draft 주입 후 리로드 → "오브젝트는 12개까지입니다. (현재 13개)" 표시, 공개 버튼 `disabled=true` |
| 3 | Draft 저장 시점에도 서버가 막음 | 13개 상태에서 저장 시도 → `저장 상태: error`, "오브젝트는 12개까지입니다. (현재 13개)" — mock `putDraft`가 client precheck와 무관하게 서버 측에서 거부 |

### §5. revision 충돌 UX — ✅ 5/5

| # | 확인 | 실측 |
|---|---|---|
| 1-2 | 탭 A 저장 성공(`revision`+1) | `window.__festaForceConflict(42)`(T-12 해소 훅)로 "다른 탭의 선행 저장"을 시뮬레이션 |
| 3 | 탭 B 저장 → 409 | dirty 편집 후 저장 → `저장 상태: error` |
| 4 | 안내 문구 | "다른 편집자가 저장했습니다. 새로고침" 표시 |
| 5 | 자동 병합 없음 | 충돌 응답 후에도 PropertiesPanel `x=0.25`(미저장 편집) 그대로 유지 — 조용히 사라지거나 덮이지 않음. "새로고침" 클릭 후에만 서버본으로 교체(`idle`) |

### §6. C-04 경고 렌더링 — ✅ 4/4

| # | 확인 | 실측 |
|---|---|---|
| 1 | `configId` 없는 AI_AGENT 배치 | AI_AGENT 1개(미연결) + DECORATION 11개(장식형) 배치 |
| 2 | `warnings`에 실리고 `errors`는 비어 진행 버튼 열림 | 공개 버튼 `disabled=false`(사전) → 공개 성공, 서버 응답 `warnings`에 "연결된 콘텐츠가 없습니다." 1건만 |
| 3 | 장식형(FURNITURE류)은 목록 제외 | DECORATION 11개는 전부 미연결이지만 warnings에 0건 — `warnOnMissingConfig:false` 카테고리 정상 제외 |
| 4 | 판정 주체 확인(서버가 rule을 errors로 옮기면 FE 무수정으로 잠김) | **재현 대신 구조적 증명** — 같은 세션 §4(`OBJECT_LIMIT`)·§6b(`AREA_OUT_OF_BOUNDS`)에서 이미 "서버 `errors[]`에 항목이 있으면 공개 버튼이 자동으로 잠긴다"는 동일 코드 경로(`preErrors`/`publishErrors` 버킷 판정)를 2회 실증했다. `CONFIG_NOT_LINKED`가 `errors`로 옮겨져도 같은 버킷 메커니즘을 타므로 FE 코드 변경 없이 잠긴다 — mock 소스를 임시 패치해 재현하지 않고 이 실증으로 갈음 |

### §6b. §10 기하 계약 — FE·서버 일치 — ✅ 2/2 (블록B에서 실측 완료분 재확인)

| # | 확인 | 실측 |
|---|---|---|
| 1-2 | 회전 반영 실물 이탈 → FE·서버 둘 다 `AREA_OUT_OF_BOUNDS` | VIDEO_SCREEN `x=2.9, rotationY=90` → FE 미리보기 "회전한 실물이 부스 영역을 벗어났습니다" + mock draft 저장도 동일 문구로 거부(`저장 상태: error`) |
| 3 | 관람 띠 미확보 → FE·서버 둘 다 `FRONT_BLOCKED` | SURVEY_KIOSK로 AI_AGENT 관람 띠(0.7m) 차단 → FE 미리보기 "관람 띠 도달 가능 비율이 50% 미만입니다" + mock publish 응답 `warnings`에도 동일 문구 |

두 결과가 항상 일치하는 이유는 우연이 아니라 구조다 — FE 실시간 미리보기(`StudioPage`)와 mock 서버(`api.mock.ts`)가 **같은 `entities/layout/geometry.ts`·`passage.ts`를 import**한다(#19 "서버와 같은 답" 전제를 코드 수준에서 보장).

### §7. facade 편집 (FR-018) — ✅ 6/6

| # | 확인 | 실측 |
|---|---|---|
| 1 | facade 패널에서 themeCode·primaryColor 변경 | `themeCode=SSAFY_BLUE`, `primaryColor=#1677C8` 입력 |
| 2 | `PUT /facade` 1건, layout `revision` 불변 | facade 저장 전후 layout draft `revision` **4 → 4**(불변). Draft/Publish 흐름과 완전 분리 재확인(T021 설계대로) |
| 3 | 새로고침 후 값 유지 | 리로드 후 `대표색=#1677C8` 유지 |
| 4 | `#FFF`(3자리) 거부 + 사유 | 입력 즉시 저장 버튼 `disabled=true`, "RRGGBB"/"6자리" 안내 문구 노출 |
| 5 | `http://` 거부 | 저장 버튼 `disabled=true`, "https" 안내 노출 |
| 5 | 2048자 초과 거부 | 2064자 입력 → 저장 버튼 `disabled=true`, "2048" 안내 노출 |
| 6 | 팔레트는 mock 대체 | `GET /booth-facade-palette` 미구현 상태 그대로(#17 진행 중) — `THEME_CODES` 4값 상수로 계획대로 대체됐음을 코드 확인(재검증 아님, 설계 확인) |

---

## 종합 판정

**8개 블록(§2·§3·§4·§5·§6·§6b·§7 — §1은 환경 설정이라 시나리오 아님) 전부 일치.** 불일치 0건 —
T026 이후 결함 재발 없음, T022·T023 §10 통합이 기존 흐름(US1~US4)과 간섭하지 않음.

## 실행 중 발견한 것(장애 아님)

- `document.body.innerText`를 React 클릭 직후 **같은 동기 스크립트 안에서** 읽으면 배치 렌더링 전
  값을 읽어 오탐할 수 있다(§4에서 "3/12"로 잘못 읽었던 사례) — 실제 상태는 `svg g` 개수나
  `sessionStorage` 같은 부수 신호로 교차 확인해야 한다. 별도 T-번호는 등록하지 않는다 —
  프로덕션 코드 결함이 아니라 이 검증 스크립트 자체의 타이밍 이슈였다.
