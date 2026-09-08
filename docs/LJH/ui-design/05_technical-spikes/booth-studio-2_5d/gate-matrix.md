# D-05 canonical Gate 대조표

> 상태 정본: [`README.md`](./README.md) · 근거 수치: [`spike-evidence.md`](./spike-evidence.md)
> 갱신: 2026-09-07 · Jira `S15P21A604-479`

---

## Gate 의 출처 — 새로 만들지 않았다

D-05 원문은 이렇게 쓴다.

> `05_technical-spikes/booth-studio-2_5d/` feasibility spike(**완료조건 15항**) PASS 후에만 정식 채택.
> — [`00_context/implementation-decisions.md`](../../00_context/implementation-decisions.md) D-05

그 15항은 [`booth-studio-r3f-asset-pipeline-plan.md`](../booth-studio-r3f-asset-pipeline-plan.md) **§47 성공 기준**에 있다.

```text
Spike PASS   6항
MVP PASS     9항
             ─────
             15항   ← D-05 가 말하는 "완료조건 15항"

자동화 PASS  7항   ← 별도 계층. 15항에 포함되지 않는다
```

아래 표의 **Canonical Gate 열은 §47 원문 그대로**다. 표현을 바꾸지 않았다.

## 상태 어휘

| 값 | 뜻 |
|---|---|
| `PASS` | 실측 근거가 있고 그 근거가 이 Gate 를 직접 만족한다 |
| `PARTIAL` | 일부만 확인됐다. 무엇이 안 됐는지 Gap 열에 적는다 |
| `NOT_TESTED` | 확인한 적이 없다 |
| `BLOCKED` | 다른 것이 먼저 움직여야 확인 가능하다 |
| `OBSOLETE` | 전제가 바뀌어 이 Gate 자체가 유효하지 않다 |

**근거 없는 PASS 를 쓰지 않는다.** "될 것 같다"는 `NOT_TESTED` 다.

---

## A. Spike PASS (§47) — 6항

| # | Canonical Gate | Status | Evidence | Gap / Next |
|---|---|---|---|---|
| A-1 | `DisplayBox01.FBX` 또는 변환 GLB가 R3F에서 정상 표시된다 | **PASS** | `-473` — DisplayBox01.FBX 52KB → GLB 26.1KB, 씬에 `DisplayBox01`(10 tri)·`DisplayBox01Glass`(230 tri) 원본 이름 그대로 로드. 네트워크 200, `[BoothAsset]` 오류 0 | — |
| A-2 | 현재 Booth 월드 좌표의 원하는 위치에 정확히 놓인다 | **PASS** | `-473` — 화분 오브젝트 world `(-0.75, -0.75)`, 바닥 정렬 `min.y = 0`. 계약 AABB 와 축별 오차 < 1.5mm | — |
| A-3 | 현재 회전값을 그대로 적용할 수 있다 | **PASS** | `-473` — `rotationY 90°` 적용 후 중심이 `(-0.75, -0.75)` 에서 이동하지 않음(제자리 회전). `-470` — 회전 드래그가 월드 평면 각도로 계산됨 | — |
| A-4 | 15° Snap UX가 깨지지 않는다 | **PASS** | `-470` — 회전 드래그 결과 `330°`(15 배수), 위치 `0.25m` 배수(`0.5`·`2.75`). bounds clamp `(3, 3)` | 세밀 회전값(비배수)은 Inspector 로 계속 입력 가능 — snap 은 편의이지 제약이 아니다 |
| A-5 | 기존 React Shell / Inspector 계약 변경이 최소다 | **PASS** | `-470` — 교체는 `BoothCanvasViewport` seam 뒤에서만. Palette·Inspector·Toolbar·ModeRail·StatusBar·editorReducer 무변경. `-473` 은 `StudioPage` 의 타입 import 경로 1줄만 변경 | — |
| A-6 | 기존 SVG 박스보다 원본 모델링과 명확히 유사하다 | **PASS** | `-473`·`-476` — 색 박스가 실제 mesh 로 대체됨. `-480` 이후 키오스크가 서 있는 카운터 형상, 의자가 원본 직물 색으로 그려지는 것을 실 크롬에서 확인. `-480` 8각도로 두 자산 모두 재확인(2026-09-08) | **이전 근거는 틀렸다.** "키오스크는 카운터 + 태블릿 형상이 그대로 보인다" 고 적었으나 그 시점 산출물은 누워 있었다(`0.5855 × 0.2794 × 0.9022`) — `!424`(LJH T-64)가 그것을 고쳤다. 8각도 비교는 [`06_visual-review/booth-studio-runtime-asset-parity.md`](../../06_visual-review/booth-studio-runtime-asset-parity.md) 로 이관 — 2026-09-08 완료, 의자 `PASS_CANDIDATE` · 키오스크 `PARTIAL`(재질 축약) |

**A 소계: PASS 6 / 6**

---

## B. MVP PASS (§47) — 9항

| # | Canonical Gate | Status | Evidence | Gap / Next |
|---|---|---|---|---|
| B-1 | 주요 Booth Asset이 실제 모델 형상으로 표시된다 | **PARTIAL** | 계약 10종 중 3종만 반입 — `DECORATION`·`FURNITURE`(의자 단품)·`SURVEY_KIOSK` | 나머지 7종 + GamePortal. 확장은 **License Gate 통과 후** |
| B-2 | 기존 이동/회전/저장 구조가 유지된다 | **PASS** | `-470`·`-473` — 선택·이동(0.25m snap)·회전(15° snap)·bounds clamp·Inspector 연동이 SVG 때와 동일. `editorReducer`·저장 계약 무변경 | — |
| B-3 | 사용자에게 Asset Import 작업이 노출되지 않는다 | **PASS** | 변환은 빌드타임 도구(`tools/assets/build-booth-assets.mjs`)에서만 일어난다. 런타임은 manifest 만 읽는다 | — |
| B-4 | 현재 Layout Asset을 우선 자동 로드한다 | **PARTIAL** | 캔버스에 놓인 오브젝트의 GLB 만 받는다(오브젝트가 없으면 요청 0). 다만 이것은 "우선순위" 설계의 결과가 아니라 필요할 때 받는 구조의 부산물이다 | 우선순위 계층(§40 Priority 0~4)은 미구현 |
| B-5 | 나머지는 Lazy Load한다 | **PARTIAL** | GLB 는 `AssetMesh` 마운트 시점에만 요청된다. 그러나 "나머지"를 미리 계획해 뒤로 미루는 prefetch 정책은 없다 | Stage B prefetch(§24) 미구현 |
| B-6 | 동일 모델은 Cache/재사용한다 | **PASS** | `canvas/boothAssetCache.ts` — URL 단위 Promise 캐시. 같은 assetCode 오브젝트가 여럿이면 `clone(true)` 로 나눠 쓴다. 실패한 Promise 는 캐시에서 제거해 재시도 경로를 남긴다 | — |
| B-7 | Asset 하나 실패해도 전체 Editor가 중단되지 않는다 | **PASS** | `AssetMesh` 가 상태를 직접 들고 실패 시 파라메트릭 박스 + 빨간 표식으로 되돌린다. manifest 부재(404)는 정상 경로로 처리하고 그 외 실패만 `console.error` 로 이유를 남긴다 | — |
| B-8 | Unity 신규 개발이 없다 | **PASS** | Unity 파일 변경 0. 읽기만 했다 — `BoothObjectRegistry.asset`·prefab·FBX·`.meta` | — |
| B-9 | Unity 원본 Asset 변경을 FE Pipeline에서 재생성할 수 있다 | **PARTIAL** | `node tools/assets/build-booth-assets.mjs` 한 줄로 재생성된다. 그러나 **수동 실행**이다 | 변경 감지·CI 자동 재생성은 §47 자동화 PASS 계층 |

**B 소계: PASS 5 / PARTIAL 4 / 9**

---

## C. canonical 15 밖이지만 판정이 필요한 것

D-05 의 15항에는 없으나, `booth-studio-r3f-asset-pipeline-plan.md` 가 **"실제 적용 단계에서 반드시 검증"** 이라고 못박은 항목이다. 15항 집계에 넣지 않고 따로 둔다.

| 항목 | Status | Evidence | Gap / Next |
|---|---|---|---|
| Unity WebGL + R3F **동시 상주** 성능 | **PARTIAL** | 확인된 것: World ↔ Studio 3왕복에서 canvas 항상 1개, R3F 컨텍스트 누적 0, context lost 0. Unity 구동 중 두 번째 WebGL2 컨텍스트 생성 성공(양쪽 lost 없음) | **동시 상주 실측은 못 했다.** 현 구조에서 Studio 진입 시 Unity 가 내려간다(`PlayerConnection::Cleanup`). Persistent GameShell 이후에야 두 컨텍스트가 같은 순간에 살아 있다 |
| 번들 오염 없음 | **PASS** | 프로덕션 `index` 청크에 `THREE`·`WebGLRenderer`·`react-three`·`BoxGeometry` 문자열 0건. main 은 오히려 427.02 → 339.46 kB 로 감소(Studio 분리 효과) | — |
| 메모리 | **PARTIAL** | Studio 진입 heap: SVG 222.7 MB / R3F 237.4 MB (+14.7 MB) | World 재진입마다 ~200 MB 증가가 관측됐으나 **R3F 와 무관**하다 — Studio 를 열지 않는 World↔booths 왕복에서 같은 폭으로 재현됐다. Unity lifecycle 별건 |

---

## D. 자동화 PASS (§47) — 7항, 별도 계층

15항 밖이다. 현재 전부 미착수이며 Runtime Asset Compiler 단계와 함께 다룬다.

| # | Canonical Gate | Status |
|---|---|---|
| D-1 | Unity Asset 변경을 CI가 감지한다 | `NOT_TESTED` |
| D-2 | FBX가 GLB로 자동 변환된다 | `PARTIAL` — 변환은 되나 수동 실행 |
| D-3 | Static Prefab 변환 가능 범위는 자동 분석된다 | `PARTIAL` — `-476` 이 지원 밖 컴포넌트를 경고로 보고한다(무시하지 않는다) |
| D-4 | 지원하지 않는 Prefab 구조는 CI에서 명시적으로 실패/경고한다 | `PARTIAL` — 경고까지. CI 는 러너가 없어 없음 |
| D-5 | Manifest가 자동 갱신된다 | `PARTIAL` — 도구 실행 시 갱신. 자동 트리거 없음 |
| D-6 | GLB URL에 Hash가 적용된다 | `NOT_TESTED` |
| D-7 | 새 배포 후 사용자가 Cache를 수동 삭제하지 않아도 최신 모델을 받는다 | `NOT_TESTED` |

---

## D-2. Runtime Asset Compiler v1 — 상태와 근거

D-05 canonical 15항 밖이다. `-480` 의 자체 판정이라 따로 둔다.

| 항목 | Status | Evidence | Gap / Next |
|---|---|---|---|
| 대표 2종이 실제로 구워진다 | **PASS** | `SURVEY_KIOSK_DEFAULT` 35,792 → 15,536 B(43.4%) · `FURNITURE_CHAIR02_WHITE` 150,116 → 79,532 B(53.0%) | 대상은 `TARGET_ASSET_CODES` 2종으로 의도적 제한 |
| 계약 AABB 정합 | **PASS** | `SURVEY_KIOSK` 축별 오차 X 0.0 · Y 4.5 · Z 0.4 mm | `CHAIR02_WHITE` 는 `typeDefault:false` 라 타입 AABB 와 다른 것이 정상 |
| 텍스처가 런타임까지 도달 | **PASS** | GLB 내부 embed 확인 — 키오스크 `metallicRoughness` 1장, 의자 `baseColor`·`normal`·`metallicRoughness` 3장. 채널 재패킹 `A(smoothness)→G(1−roughness)·R(metallic)→B` 적용 | `!422` 이전에는 `AssetMesh` 가 계약 색으로 덮어써 **받은 텍스처를 버리고 있었다** |
| Unity `.mat` ↔ 런타임 material | **PASS** | 대표 2종 모두 `baseColor`·`metalness`·`roughness` 값 일치 | 재현: `node tools/assets/parity-report.mjs` |
| 8각도 시각 parity | **PASS** | 2026-09-08 실 Chrome — 대표 2종 `0·45·90·135·180·225·270·315` 전부. 키오스크는 배면 2단 선반까지, 의자는 등판 뒷면 색차까지 확인 ([parity §3·§4](../../06_visual-review/booth-studio-runtime-asset-parity.md)) | 캡처는 CDP 스크린샷이 아니라 `canvas.toDataURL()` 로 뽑는다 — `visibilityState: hidden` 을 타지 않는다(LJH T-56 우회) |
| 다중 재질 보존 | **PASS** | `-527` 해소. 키오스크 런타임 GLB `materials` **3개**가 원본 이름 그대로 구워진다 — `Tablet.mat`(baseColor `Tablet.jpg` 38,688 B → 1,126 B webp) · `AluminiumBrushed.mat` · `PlasticWhite.mat`. primitive 도 1:1:1 로 갈린다 | GLB 15,536 → 27,408 B(+76%). 텍스처가 3장 늘어난 값이라 예산 판단은 coverage 확대 때 다시 본다 |
| Source(Unity Editor) 렌더 대조 | **NOT_OBTAINED** | — | 이 환경에 Unity Editor 가 없다. 원본 쪽 근거는 계약 AABB·`.mat`·원본 텍스처로 대신한다 |
| normal map 화면 기여 | **NOT_TESTED** | — | Booth Studio 줌 상한 120%, 오브젝트 0.5 m 라 표면 요철을 분간할 수 없다 |

```text
COMPILER_V1 = PASS_CANDIDATE
SURVEY_KIOSK_DEFAULT      PASS_CANDIDATE   (형상 PASS · 재질 3종 보존 PASS)
FURNITURE_CHAIR02_WHITE   PASS_CANDIDATE
```

**8각도는 해소됐다.** 두 회차를 막고 있던 것은 렌더가 아니라 캡처 경로였다 — 그 경로를 안 쓰는 방법이 있었다.

**다중 재질도 해소됐다**(`-527`). 마지막까지 남았던 "원본 속성 하나가 재현되지 않는다" 가 없어져 `COMPILER_V1` 을 `PASS_CANDIDATE` 로 올린다.

**`PASS` 가 아닌 이유는 하나** — normal map 의 화면 기여를 이 밀도에서 분리하지 못했고 그것을 `known differences` 에 적어 두었다. `ADOPTED` 는 D-05 canonical 15 Gate reconciliation 이 선행한다(§E).

## D-3. 의도적으로 미룬 것 — thumbnail / Asset Library 연결

**빠뜨린 것이 아니라 미룬 것이다.** 다음 사람이 구분할 수 있게 남긴다.

| | 상태 |
|---|---|
| runtime manifest | `thumbnail: "<assetCode>.webp"` **이미 낸다** (키오스크 4,230 B · 의자 5,958 B, 256px) |
| FE 타입 | `BoothAssetEntry.thumbnail` **이미 있다** (`model/boothAssetManifest.ts:26`) |
| 소비처 | **없다.** 팔레트(`ui/shell/AssetPalette.tsx:56`)가 `data-kind` CSS 아트를 쓴다 |

지금 연결하면 팔레트 15칸 중 실사진이 1~2칸뿐이라 표현이 두 어휘로 갈린다. 그 커버리지 부족은 기술 문제가 아니라 **B-1 의 벤더 라이선스 게이트**에 묶여 있다. 자산이 채워진 뒤 한 번에 바꾼다.

추적: Jira `S15P21A604-509` · GitLab `#146`

## E. 집계와 판정

```text
canonical 15 Gate
  PASS         11
  PARTIAL       4     (B-1 · B-4 · B-5 · B-9)
  NOT_TESTED    0
  BLOCKED       0
  OBSOLETE      0

D05_R3F = PASS_CANDIDATE
D05_R3F_ADOPTION = PENDING
```

**PARTIAL 4건이 남아 있으므로 15항 PASS 가 아니다.** D-05 원문이 "15항 PASS 후에만 정식 채택" 이라고 못박았으므로 현재는 채택 조건이 성립하지 않는다.

남은 4건 중 **B-1 은 벤더 에셋 라이선스 게이트에 묶여 있다** — 기술 문제가 아니다. B-4·B-5·B-9 는 Runtime Asset Compiler 단계에서 자연히 다뤄진다.

### 채택으로 가는 경로

```text
1. B-4 · B-5 · B-9   Runtime Asset Compiler v1 에서 해소
2. B-1               Source Package License Gate 통과 후 확장
3. C의 동시 상주      Persistent GameShell 이후 실측
4. 그 뒤에 별도 결정  → ADOPTED
```

3번은 canonical 15항 밖이라 채택의 **필수 조건은 아니다.** 다만 미검증 사실을 알고 채택하는 것과 모르고 채택하는 것은 다르므로 여기 남긴다.
