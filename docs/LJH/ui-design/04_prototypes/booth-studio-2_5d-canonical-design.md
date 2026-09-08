# Booth Studio 2.5D — 최종 설계 정본

> **STATUS: 정본 (CANONICAL)** · 2026-09-08 · 이정헌(FE) · Jira `S15P21A604-551`
> 기준 develop `21c5a4be` (`-527` 다중 재질 · `-550` 테스트 안정화 반영 후)
>
> 이 문서가 2.5D 설계의 정본이다. 조사·스파이크 문서는 근거로 남고 판정은 여기서 한다.
> 앞선 문서와 충돌하면 **이 문서가 이긴다.**

| 이 문서가 대체·흡수하는 것 | 관계 |
|---|---|
| `05_technical-spikes/booth-studio-r3f-asset-pipeline-plan.md` | 상세 실행 설계·조사자료. **결정 지위 없음**(그 문서 스스로 명시) |
| `05_technical-spikes/booth-studio-2_5d/gate-matrix.md` | Gate 상태 정본 — 유지. 이 문서는 그것을 인용만 한다 |
| `06_visual-review/booth-studio-runtime-asset-parity.md` | 시각 parity 기록 — 유지 |
| `verify/booth-asset-supply-chain.md` · `verify/booth-asset-mapping-gap.md` | 실측 근거 — 유지 |
| `00_context/implementation-decisions.md` D-05 | 채택 결정 정본 — 유지. 이 문서는 그 아래에 놓인다 |

---

## 1. 목적 / 범위

**목적** — 실제 Unity 자산을 thumbnail 기반·종류별 Asset Library 에서 찾아 배치하고, 초보 사용자는 미리 배치된 Booth Template 으로 시작하며, 그 결과를 그대로 자유 편집할 수 있게 한다.

**제품 구조 — 두 축을 분리한다.**

```text
Booth Interior   자유 배치형 2.5D Editor          내부 오브젝트를 사용자가 놓는다
Facade           선택형 2.5D Customization        완성형 외부 에셋 1개를 고른다
```

**범위 밖** — Facade 자유 편집기, Unity 런타임 렌더링 변경, 아바타·게임 스튜디오.

## 2. 현재 구조 (develop `21c5a4be` 실측)

```text
Unity source     ExpoKit FBX 22 · prefab 19 · 부스 registry 11 · ithappy · Laptop
  ↓ 수동         tools/assets/{source,unity-prefab}.mjs  — 중첩 prefab·다중 재질 해석
Compiler         compiler/pipeline.mjs  — license → geometry → material → strip → emit → thumbnail
  ↓ 수동 실행    .generated/runtime/{*.glb,*.webp,manifest.json}   [.gitignore]
배포             ✗ 미연결 — vite configureServer(dev 전용). 라이선스 게이트는 PASS 로 풀렸다
FE loader        model/boothAssetManifest.ts · canvas/boothAssetCache.ts (URL 단위 lazy)
R3F              canvas/R3FBoothRenderer.tsx · AssetMesh.tsx — 고정 orthographic iso
Studio UI        ui/shell/AssetPalette.tsx  ← **하드코딩 15항 + CSS data-kind 아트**
```

**끊긴 지점 둘** — ⑴ 좌측 팔레트가 manifest 를 안 읽는다 ⑵ 산출물이 프로덕션 빌드에 안 들어간다.

**팔레트 실측** — 15항 중 실제 GLB 가 뜨는 것은 **1개**(설문 키오스크, typeDefault 적중). 팔레트 assetCode 7종과 manifest 코드 2종의 교집합은 **0**이다.

## 3. 최종 아키텍처

```text
                    Unity / Source Assets
                            │
                    Asset Inventory  (§7)
                            │
                Canonical assetCode  (§8)
                            │
                Runtime Asset Compiler  (§6)
                            │
            Common Runtime Manifest  (§13)
                     /               \
         Booth Domain                Facade Domain
              │                           │
     Asset Library (§10)          Facade Selection (§12)
     Booth Template (§11)
              │                           │
   자유 배치형 2.5D Editor        선택형 2.5D Customization
```

**같이 쓰는 것** — source/prefab resolver · material·texture 파이프라인 · GLB · thumbnail · loader/cache · manifest core
**분리하는 것** — domain metadata · UI category · interaction UX · save contract

**SSOT 역할 분리(유지)**

```text
Spring Catalog     비즈니스 SSOT   — 판매·보유·잠금·가격. docs/26:108 (#18, 2026-08-21 확정)
Runtime Manifest   렌더링 SSOT     — url·bounds·thumbnail·재질. Compiler 가 생성
Unity Registry     런타임 조회 표  — type:assetCode → prefab
```

## 4. 좌표 / 편집 계약 — 바꾸지 않는다

```text
원점        부스 바닥 중앙 (헌법 21조). Compiler 가 피벗을 여기에 맞춘다
이동        X/Z 자유, Y 고정 0 (계약 Position.y 는 항상 0)
회전        rotationY [0,360) 자유. 15° snap 은 편의이지 강제가 아니다
스냅        0.25 m 이동 스냅
경계        부스 6×6×2.72 m. 이탈 판정은 계약 AABB 대조
```

## 5. R3F Renderer — 유지

`D-05 = PASS_CANDIDATE`. 고정 orthographic isometric, `frameloop='demand'`, `preserveDrawingBuffer: true`.

`TemporaryIsoRenderer` 는 **폐기 아님** — `BoothCanvasViewport:52` 의 Suspense fallback 으로 실동작한다.

## 6. Runtime Asset Compiler — `-527` 이후를 baseline 으로

**유지(재구현 금지)**

```text
FBX load · 중첩 prefab resolve · transform override(CRLF 포함)
다중 재질 보존(-527) · 축/단위/피벗 정규화
texture 재패킹·webp · GLB emit · thumbnail 256px · manifest
license gate(source-packs.lock.json)
```

**추가 검토(임의 수치 금지)**

```text
target-tri 기반 반복 simplify   현재는 tri>2000 이면 ratio 0.6 **1회**.
                               CarnivalKit 최대 897k tri 에는 도달하지 못한다
공유 텍스처 중복 감축           재질이 자산마다 개별 embed 된다. CarnivalKit 은 재질 12종을
                               여러 부스가 공유하는데 지금 구조로는 중복이 된다
build-time CI 연결              §15
```

## 7. Asset Inventory

출처·라이선스는 `#148` 회신 + `AssetOrigin` 전수 실측으로 확정(`verify/booth-asset-supply-chain.md` §3-2). 세 팩 모두 `ALLOW_RUNTIME_COMPILE`.

```text
BOOTH_INTERIOR   ExpoKit(Stand Expo Pack 114974)   FBX 22 · prefab 19
                 ithappy/Casino_Free(393074)        AiAgent 1
                 _Project/Models/Laptop(90315)      Laptop 1
FACADE           CarnivalKit(Carnival & Funfair Stalls 398112)  완성형 18 / 부품 22 / 소품 8
SHARED           없음 — 두 팩이 자산을 공유하지 않는다(재귀 추적 확인)
WORLD_ONLY       Palmov 50 · Hyper casual castles 27 · danthaigames 1
DEV_ONLY         ExpoKit/TabletCube (참조 0)
```

### 7-1. Compiler 실측 (부스 조립체 8종)

| prefab | tri | GLB | 재질 | 크기(m) |
|---|---|---|---|---|
| SurveyKiosk | 286 | 27,408 | 3 | 0.62×0.93×0.32 |
| ConsultationDesk | 2,128 | 113,656 | 3 | 1.86×0.92×1.16 |
| VideoScreen | 1,300 | 44,920 | 2 | 2.70×2.10×0.30 |
| ProjectPanel | 944 | 34,988 | 2 | 1.55×2.90×0.51 |
| RecruitmentBoard | 880 | 32,820 | 2 | 3.00×2.90×0.51 |
| Furniture | 2,550 | 62,124 | 4 | 1.43×0.75×1.58 |
| Decoration | 480 | 37,580 | 3 | 0.60×1.61×0.60 |
| LikeVote | 370 | 29,316 | 3 | 0.62×1.23×0.32 |

**합계 373.8 KB + thumbnail 31.7 KB.** URL 단위 lazy 라 배치된 것만 받는다.

### 7-2. 컴파일 실패 3종 — 확장 시 실제 리스크

```text
Laptop      텍스처가 .tga (sharp 미지원)              → 포맷 갭
AiAgent     skinned mesh + Animator                    → 지원 범위 밖
BoothShell  Prefab Variant + base guid 저장소 결손     → Variant 미지원 + 원본 결손
```

세 건 모두 **파이프라인 확장이냐 자산 재저장이냐**가 미결이다(§21).

## 8. Canonical assetCode

### 8-1. 명명 규칙

```text
형식        <DOMAIN>_<FAMILY>[_<VARIANT>]      대문자 스네이크
DOMAIN      기술 분류(§9). 접두로 namespace 를 가른다
FAMILY      UI category 파생 키(§10-2). 이 세그먼트가 좌측 메뉴 그룹을 정한다
VARIANT     색·형태 변형

금지        _DEFAULT 를 코드에 넣지 않는다 — typeDefault 는 manifest/Registry 의 **flag** 다
재사용      폐기한 코드를 다시 쓰지 않는다. 카탈로그에서 내리고 값은 영구 예약
```

**근거** — Spring `catalog_items` 가 이미 같은 축을 갖는다: `item_code`(정체성) · `item_type`(도메인) · `equip_slot`(하위 슬롯) · `asset_key`(클라이언트 식별자). `V16` 주석이 *"asset_key 는 … 부스 파츠(assetCode 문자열)"* 로 부스를 이미 예고한다. 새 스키마를 발명하지 않는다.

### 8-2. Booth Interior — 단품 (ExpoKit prefab)

| assetCode | prefab | FBX | 재질 |
|---|---|---|---|
| `FURN_CHAIR_01_BLUE` | Chair01_blue | Chair01 | PlasticBlue |
| `FURN_CHAIR_01_ORANGE` | Chair01_orange | Chair01 | PlasticOrange |
| `FURN_CHAIR_01_WHITE` | Chair01_white | Chair01 | PlasticWhite |
| `FURN_CHAIR_02_WHITE` | Chair02_White | Chair02b | Chair02b |
| `FURN_COUNTER_01` | Counter01 | Counter01·Cloth01·Top·Shelf | AluminiumBrushed · PlasticWhite |
| `FURN_COUNTER_01B` | Counter01b | Counter01·Cloth·Top·Shelf | 동상 |
| `FURN_COUNTER_02` | Counter02 | Counter02·Cloth·Top·Shelf | 동상 |
| `FURN_TABLE_ROUND` | TableRound | TableRound | Aluminium · PlasticWhite |
| `FURN_TABLE_SQUARE` | TableSquare | TableSquare | 동상 |
| `STRUCT_PANEL_01` | Panel01b | Panel01b | AluminiumBrushed |
| `STRUCT_PANEL_02` | Panel02b | Panel02b | AluminiumBrushed |
| `STRUCT_PANEL_03` | Panel03 | Panel03 | AluminiumBrushed |
| `STRUCT_TRUSS_BASE` | TrussBase | TrussBase | Aluminium |
| `STRUCT_TRUSS_VERTICAL` | TrussVertical | TrussVertical | Aluminium |
| `STRUCT_TRUSS_HORIZONTAL_LAMP` | TrussHorizontal_Lamp02 | TrussHorizontal · Lamp02b | Aluminium · Light · PlasticBlack |
| `DISP_BOX_01` | DisplayBox01Stuff | DisplayBox01 | AluminiumBrushed · Glass · PlasticWhite |
| `DISP_STAND_PLASTIC_01` | StandPlastic01 | StandPlastic01 | Glass |
| `DEVICE_TABLET` | Tablet | Tablet | AluminiumBrushed · Tablet |

`TabletCube` 는 참조 0 이라 코드를 주지 않는다(DEV_ONLY).

### 8-3. Booth Interior — 조립체 (registry prefab)

| assetCode | prefab | ObjectType | typeDefault |
|---|---|---|---|
| `BOOTH_KIOSK_SURVEY` | SurveyKiosk | `SURVEY_KIOSK` | ✔ |
| `BOOTH_DESK_CONSULT` | ConsultationDesk | `CONSULTATION_DESK` | ✔ |
| `BOOTH_SCREEN_VIDEO` | VideoScreen | `VIDEO_SCREEN` | ✔ |
| `BOOTH_PANEL_PROJECT` | ProjectPanel | `PROJECT_PANEL` | ✔ |
| `BOOTH_BOARD_RECRUIT` | RecruitmentBoard | `RECRUITMENT_BOARD` | ✔ |
| `BOOTH_STAND_LIKE` | LikeVote | `LIKE_VOTE` | ✔ |
| `BOOTH_DESK_LAPTOP` | Laptop | `LAPTOP` | ✔ |
| `BOOTH_AGENT_AI` | AiAgent | `AI_AGENT` | ✔ |
| `FURN_SET_TABLE_CHAIRS` | Furniture | `FURNITURE` | ✔ |
| `DISP_SET_BOX_01` | Decoration | `DECORATION` | ✔ |

### 8-4. 기존 충돌 해소

**두 건 다 "같은 코드, 다른 자산" 이었다.**

```text
DECORATION_DEFAULT
  Unity registry = Decoration.prefab (DisplayBox01Stuff 조립)
  FE config      = Stands/DisplayBox01.FBX 단품
  → 서로 다른 자산이므로 코드를 가른다
     DISP_SET_BOX_01 (조립·typeDefault) · DISP_BOX_01 (단품)

FURNITURE 타입 기본
  Unity = Furniture.prefab (테이블1 + 의자3, 1.43×0.75×1.58)
  FE    = FURNITURE_CHAIR01 (의자 단품, 0.38×0.45×0.38)
  → 타입 기본은 Unity 를 따른다: FURN_SET_TABLE_CHAIRS (typeDefault ✔)
     FE 의 단품은 FURN_CHAIR_01_WHITE 로 개명하고 typeDefault=false
```

### 8-5. mock → canonical 매핑

| 기존 목업 코드 | 처리 |
|---|---|
| `WALL_PLAIN` | → `STRUCT_PANEL_02` |
| `COUNTER_GRAPHIC` | → `FURN_COUNTER_02` (그래픽 변형은 원본에 없다) |
| `SHELF` | → `FURN_COUNTER_01` (CounterShelf 는 카운터의 부품이다) |
| `TRUSS_BEAM` | → `STRUCT_TRUSS_HORIZONTAL_LAMP` |
| `TRUSS_PILLAR` | → `STRUCT_TRUSS_VERTICAL` |
| `TRUSS_GATE` | **폐기** — 단일 모델이 없다. 기둥2+빔1 조합은 사용자가 놓는다 |
| `PLANT` | **폐기** — 원본이 저장소에 없다 |
| `DECORATION_DEFAULT`·`FURNITURE_CHAIR01`·`SURVEY_KIOSK_DEFAULT`·`FURNITURE_CHAIR02_WHITE` | Compiler config 코드. §8-2·8-3 로 개명 |

폐기 코드는 **예약**한다 — 저장된 layout 에 남아 있을 수 있으므로 재사용하지 않고, 조회 실패 시 타입 기본으로 떨어지는 기존 동작(`pickAsset`)을 그대로 둔다.

### 8-6. Facade 18종

| assetCode | prefab | 그룹 |
|---|---|---|
| `FACADE_BOOTH_RING_TOSS` · `_WATER_GUN` · `_CAN_KNOCKDOWN` · `_DUCK_POND` · `_BALLOON_DART` · `_BASKETBALL` | `PF_Combined/*` | 게임 부스 6 |
| `FACADE_BOOTH_FORTUNE_TELLER` · `_TICKET` · `_PRIZE_WALL` · `_HIGH_STRIKER` | root prefab | 독립 부스 4 |
| `FACADE_STAND_CANDY_APPLE` · `_FUNNEL_CAKE` · `_LEMONADE` | root prefab | 노점 3 |
| `FACADE_CART_COTTON_CANDY` · `_POPCORN` · `_HOT_DOG` · `_ICE_CREAM` · `_SHAVED_ICE` | root prefab | 카트 5 |

**Facade 1개 = prefab 1개 = assetCode 1개 = GLB 1개 = thumbnail 1개.** 부품 22·소품 8 은 완성형 내부 구성이라 코드를 주지 않는다.

## 9. Asset Domain / UI Category / ObjectType — 세 축은 다르다

```text
Asset Domain     자산의 본질적 분류. 자산에 붙고 UI 가 바뀌어도 안 바뀐다
                 FURNITURE · STRUCTURE · DISPLAY · DEVICE · DECORATION · CHARACTER · FACADE

UI Category      사용자가 Library 에서 찾는 메뉴. 화면 소관, 자유롭게 재편
                 의자 · 테이블 · 카운터 · 패널 · 트러스 · 전시장비 · 조명 · 소품

ObjectType       서버가 무엇으로 취급하나. 계약 10종, 고정
                 AI_AGENT · VIDEO_SCREEN · PROJECT_PANEL · SURVEY_KIOSK · RECRUITMENT_BOARD
                 · CONSULTATION_DESK · LAPTOP · LIKE_VOTE · FURNITURE · DECORATION
```

**규칙**

- 하나의 domain 이 여러 UI category 에 나타날 수 있다(`FURNITURE` → 의자·테이블·카운터)
- **UI category 개편 때문에 assetCode·domain 을 바꾸지 않는다**
- 세 축을 같은 enum 으로 묶지 않는다

지금은 `visualAssets.ts`(UI 섹션 5) · `types.ts`(ObjectType 10) · `objectAppearance.ts`(렌더 속성 10) 세 표가 각각 하드코딩돼 있고 **모두 ObjectType 을 키로 쓰면서 의미가 다르다.** 이것이 자산 확장 시 조합 폭발의 원인이다.

## 10. Asset Library

### 10-1. UI 구조

```text
┌─────────────────────────────┐
│ Asset Library               │
│ [검색]                      │
│ 전체 / 즐겨찾기 / 보유      │
│ ▼ 의자      [t][t][t]       │
│ ▼ 테이블    [t][t]          │
│ ▼ 카운터    [t][t][t]       │
│ ▼ 패널      [t][t][t]       │
│ ▼ 트러스    [t][t][t]       │
│ ▼ 전시장비  [t][t]          │
│ ▼ 소품·장식 [t][t]          │
└─────────────────────────────┘
```

item 최소 필드: `thumbnail` · `displayName` · `assetCode` · `uiCategory` · `lock/ownership`.

### 10-2. UI Category 는 manifest 에 넣지 않는다

**결정: FE presentation mapping.** 근거 셋.

1. UI category 는 **제품·메뉴 결정**이라 UX 개편마다 바뀐다. manifest 는 렌더링 SSOT 라 안정해야 한다
2. manifest 에 넣으면 카테고리 변경이 **Compiler 재실행**을 요구한다 — 자산이 안 바뀌었는데 산출물을 다시 굽는 것은 인과가 뒤집힌 것이다
3. 하드코딩으로 돌아가지 않을 방법이 있다 — **assetCode 의 FAMILY 세그먼트를 키로 쓴다**

```text
FURN_CHAIR_01_BLUE   → FAMILY=CHAIR   → 의자
FURN_COUNTER_01      → FAMILY=COUNTER → 카운터
STRUCT_TRUSS_BASE    → FAMILY=TRUSS   → 트러스
```

FE 는 **자산 27행 표가 아니라 FAMILY 8행 표**를 든다. UI 개편은 그 8행만 고치고 assetCode 는 그대로다. 새 자산이 들어와도 FAMILY 가 같으면 표를 안 고쳐도 분류된다.

### 10-3. 로딩

```text
목록 표시   thumbnail(webp 2~8 KB)만 받는다. GLB preload 금지
클릭/드래그 assetCode → manifest lookup → GLB lazy load → Canvas 배치
캐시        boothAssetCache 의 URL 단위 Promise 캐시 그대로. 실패는 캐시에서 제거
```

## 11. Booth Template

### 11-1. 무엇인가

**새 3D 자산이 아니다.** 기존 `assetCode` 여러 개 + 각 오브젝트의 위치·회전을 미리 정의한 **layout 생성 recipe** 다.

```text
Template 자체의 assetCode      없다
Template 내부 object 의 assetCode   canonical 값을 그대로 쓴다
```

### 11-2. 데이터

```ts
interface BoothTemplate {
  templateCode: string;
  name: string;
  description?: string;
  thumbnail?: string;          // 없으면 구성 자산 thumbnail 조합으로 대체
  objects: Array<{
    assetCode: string;
    objectType: ObjectType;
    position: { x: number; y: 0; z: number };
    rotationY: number;
  }>;
}
```

### 11-3. UX

```text
Booth Studio 진입 또는 Library 상단   [빠른 시작] [에셋]

빠른 시작
  - 빈 부스
  - 기본 전시형
  - 상담형
  - 영상 홍보형

선택 → Preview → 적용 → LayoutObject 여러 개 생성
     → 이후 이동·회전·삭제·추가 전부 자유
```

**Template 은 초기 배치 편의 기능일 뿐 이후 편집을 제한하지 않는다.** 적용 후에는 일반 Editor 와 **완전히 같은 데이터 구조**를 쓴다 — 전용 renderer·저장 포맷을 만들지 않는다.

### 11-4. 실제 구성 (현 inventory 기준, 가짜 자산 없음)

```text
기본 전시형   BOOTH_PANEL_PROJECT · FURN_COUNTER_01 · FURN_CHAIR_01_WHITE · DISP_SET_BOX_01
상담형        BOOTH_DESK_CONSULT · FURN_CHAIR_02_WHITE · STRUCT_PANEL_02
영상 홍보형   BOOTH_SCREEN_VIDEO · FURN_COUNTER_02 · DISP_SET_BOX_01
```

### 11-5. 기존 `TEMPLATE_PRESETS` 재판정

| 필드 | 판정 |
|---|---|
| `themeCode` · `primaryHex` | **KEEP** — Facade 계약에 실재하고 저장된다 |
| `accentHex` · `floorHex` | **DEPRECATE** — 목업 전용, 저장 안 됨 |
| `label` · `description` | **KEEP** — 새 구조로 이관 |
| 전체 구조 | **REPLACE** — 색 프리셋이 아니라 `BoothTemplate`(오브젝트 배치)으로 |

migration: 현 4종은 **색 프리셋**이고 새 Template 은 **배치 recipe** 다. 성격이 달라 1:1 이관이 아니다. 색 프리셋은 Facade 모드의 테마 선택으로 남기고, Template 은 §11-4 로 새로 만든다.

## 12. Facade

```text
자산     CarnivalKit 완성형 18종 (§8-6)
UX       해금/보유 → 목록에서 하나 선택 → 2.5D preview → 적용
저장     facadeAssetCode 1필드   ← 계약 확장 필요(§19)
구조     prefab 1 = assetCode 1 = GLB 1 = thumbnail 1
```

**Facade 를 Booth Template 구성요소 조합으로 만들지 않는다.** 둘은 다른 축이다.

```text
Booth Template   내부 LayoutObject 초기 배치 세트
Facade           외부 완성형 에셋 1개 선택
```

기존 `BoothShell`·`Panel`·`Truss` 는 **Facade 본체가 아니다** — 셸은 시스템이 항상 그리는 무대이고, Panel/Truss 는 내부 배치 자산으로 남는다.

## 13. Runtime Manifest v2

```jsonc
{
  "version": 2,
  "generatedAt": "...",
  "emission": "productionCandidate",
  "assets": [{
    // ── common core ──
    "assetCode": "BOOTH_KIOSK_SURVEY",
    "domain": "DEVICE",
    "url": "BOOTH_KIOSK_SURVEY.glb",
    "thumbnail": "BOOTH_KIOSK_SURVEY.webp",
    "bytes": 27408,
    "triangles": 286,
    "bounds": { "min": [...], "max": [...] },
    "materials": [ { "source": "Tablet.mat", "baseColor": [...], "metalness": 0.2, "roughness": 0.07 } ],
    "source": { "prefab": "...", "unitScale": 0.0254, "upAxis": "yUp" },

    // ── domain block ──
    "booth":  { "objectType": "SURVEY_KIOSK", "typeDefault": true },
    "facade": null
  }]
}
```

`uiCategory` 는 **넣지 않는다**(§10-2). `domain` 은 기술 분류라 넣는다.

v1 과의 차이: `version` 2 · `domain` 신설 · `materials` 복수(`-527`) · `booth`/`facade` 블록. `objectType`·`typeDefault` 는 v1 에서 최상위였던 것을 `booth` 블록으로 옮긴다.

## 14. Spring Catalog 연계

```text
비즈니스 SSOT = Spring (docs/26:108, #18 확정 — 재논의 없음)
현재 상태     catalog_items 는 AVATAR_PART 97행. 부스 장식 0행.
              스키마도 아바타형(equip_slot). spec 012 "장식 범위 검토 대기 · P1(2차 MVP)"
FE 조회       StudioPage:33 이 'BOOTH_DECOR' 로 조회 → backend 미정의 → 200 + 빈 배열
```

**결론** — 카탈로그 결선은 BE backlog 다. 그때까지 Asset Library 는 **manifest 만으로 동작**하고 잠금·가격은 표시하지 않는다. `BOOTH_DECOR` 문자열은 BE 가 장식 범위를 구현할 때 확정하고 FE 가 맞춘다(§19).

## 15. Build-time pipeline

실측으로 후보가 하나로 좁혀진다.

```text
ci/build      front) npm ci && npm run build       ← 저장소 전체 체크아웃, festa-unity 있음
ci/package    docker build --context festa-frontend/   ← festa-unity 가 컨텍스트 밖
Dockerfile    FROM base AS build; COPY . .; RUN npm run build
.dockerignore .generated/ · public/assets/booth/     ← 라이선스 사유(이제 PASS)
```

**이미지 안에서는 컴파일이 불가능하다.** 따라서:

```text
① CI 호스트에서 컴파일    ci/build 에 자산 단계 추가
② 산출물을 이미지로       .dockerignore 제외를 풀고 prod 단계에서 컨텍스트에서 직접 복사
                          COPY .generated/runtime /usr/share/nginx/html/assets/booth-runtime
③ nginx 변경 0            try_files $uri 가 그대로 서빙(dev 라우트와 같은 경로)
④ 런타임 주입             40-runtime-config.sh 에 PUBLIC_BOOTH_ASSET_BASE 추가(CDN 이전용)
```

로컬 dev 는 `configureServer` 가 이미 내보내므로 증분 컴파일이 불필요하다.

## 16. Runtime loading / cache — 이미 있다

```text
lazy load        boothAssetCache.loadGlb — URL 단위 on-demand + Promise 캐시
실패 무효화      실패한 약속은 캐시에서 제거(재시도 가능)
렌더러 분할      BoothCanvasViewport:15 lazy(() => import('./R3FBoothRenderer'))
base seam        runtime.ts:53 boothAssetBase() — CDN override 지원 + 테스트 있음
thumbnail 생성   Compiler 가 256px webp 생성, manifest 에 실림
thumbnail 소비   **없음** — 이번 §10 에서 연결한다
```

## 17. 성능 / 경량화

```text
Booth interior   8종 합계 373.8 KB. 24종으로 늘려도 ~1.2 MB, 전량 동시 로드는 없다
Facade           표본 20종 73.2 MB(텍스처 제외), 최대 20.4 MB(897k tri)
                 → 현재 simplify(2000 tri 초과 시 ratio 0.6 1회)로는 웹 예산에 못 든다
```

**임의 상한을 선언하지 않는다.** 지배 변수는 둘 — ⑴ Facade 감축 정책 ⑵ 공유 텍스처 중복. 둘 다 실험 근거를 만든 뒤 정한다(§20 P6).

## 18. 기존 mock migration

| 대상 | 판정 | 대체 |
|---|---|---|
| `LAYOUT_PALETTE` 하드코딩 15항 | **REPLACE** | manifest 소비 Library(§10) |
| CSS `data-kind` 썸네일 18종 | **REPLACE** | `manifest.thumbnail` |
| 목업 assetCode 7종 | **REMOVE_LATER** | §8-5 매핑. 값은 예약 |
| `FACADE_PALETTE_SECTIONS` 9 | **REMOVE_LATER** | Facade Selection UI(§12) |
| `TEMPLATE_PRESETS` `accentHex`·`floorHex` | **DEPRECATE** | 저장 계약에 없음 |
| `BOOTH_DECOR` 문자열 | **REPLACE** | BE 확정값(§14) |
| `TemporaryIsoRenderer` | **KEEP** | Suspense fallback 으로 실동작 |

## 19. 파트별 변경 요청 — 임의 구현 금지

### 19-1. 계약 `assetCode` 범위 (BE + Unity + FE)

```text
CURRENT   entities/layout/types.ts:29
          assetCode?: string;  // 장식형(FURNITURE·DECORATION) 외형 선택 코드
TARGET    ObjectType 10종 전체에서 assetCode 허용
이유      Panel·Truss·Display·Device 를 실자산으로 배치하려면 필요하다.
          지금은 그 8종에 외형 선택이 원천적으로 불가능하다
영향      BE   LayoutJson 검증
          Unity BoothObjectRegistry — 이미 type:assetCode 2단 조회를 지원한다(변경 불요)
          FE   pickAsset·팔레트
Gate      3파트 합의 필요. 합의 전에는 FURNITURE·DECORATION 범위에서만 실자산을 낸다
```

### 19-2. Facade 저장 (BE)

```text
현재      PUT /booths/{id}/facade 존재. themeCode·primaryColor·signText·logoUrl 4필드
요청      facadeAssetCode 1필드 추가
```

### 19-3. Spring 부스 카탈로그 (BE)

```text
spec 012 장식 범위(P1, 2차 MVP) 구현. item_type 문자열 확정 필요
```

### 19-4. Unity Registry 데이터 (Game)

```text
§8 canonical 코드로 type:assetCode 엔트리 채우기. 구조 변경은 불필요
```

## 20. 구현 단계 / 우선순위

```text
P0  최종 설계 문서                     ← 이 문서
P1  canonical assetCode 표 확정         ← §8
P2  Asset Library 구조 구현             FE 내부. manifest consumer · thumbnail · FAMILY 그룹
P3  Booth Template 모델 + 초기 UI       FE 내부. 실제 assetCode 기반
P4  Manifest v2                         Compiler + FE 타입
P5  build-time pipeline                 §15
P6  Facade 경량화 실험                  target-tri · 텍스처 공유
P7  Facade Selection UI                 19-2 합의 후
```

**P2·P3 는 계약 변경도 타 파트 승인도 필요 없다** — FE 내부 구현이라 즉시 착수한다.

## 21. 완료 기준 / 미결정

**완료 기준**

```text
Asset Library    manifest 를 소비한다. CSS mock 썸네일 0. FAMILY 그룹으로 묶인다
Template         선택 → LayoutObject 생성 → 이후 일반 편집과 동일 데이터
Facade           18종 선택·미리보기·적용
Manifest v2      domain + booth/facade 블록
production       dist 에 자산이 실려 나간다
```

**미결정**

```text
① 계약 assetCode 범위 확장 (§19-1)          3파트 합의
② FURNITURE·DECORATION 타입 기본 통일 (§8-4) Unity 기준 채택안 통보 필요
③ Facade 감축 정책 (§17)                     실험 후 결정. 임의 수치 금지
④ Laptop(.tga)·AiAgent(skinned)·BoothShell(Variant) 3종   파이프라인 확장 vs 자산 재저장
⑤ Spring 부스 카탈로그 item_type 문자열       BE 확정 대기
```
