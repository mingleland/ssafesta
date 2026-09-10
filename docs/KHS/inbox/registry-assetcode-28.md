# BoothObjectRegistry — canonical assetCode 28행 등록 작업표 (GitLab #146, S15P21A604-509 대응)

> 근거: colosair 2026-09-08 21:52 회신(#146 note). FE 가 내는 `(type, assetCode)` 를 Registry 가 **같은 값으로** 받는다. 중간 변환 계층 없음.
> 프리팹은 `Assets/_Project/Art/Booth/ExpoKit/Prefabs/**` 에 이미 있는 것을 쓴다 — 코드 이름이 그 프리팹 이름에서 나왔다.
> 기존 부스 래퍼(`Prefabs/Booth/Furniture.prefab`)는 ExpoKit 프리팹을 **중첩 프리팹으로 감싼 것**이다. 새 코드도 같은 방식(래퍼 + `BoothRuntimeObject`)으로 만들지, ExpoKit 프리팹을 직접 등록할지는 에디터에서 Furniture.prefab 의 오버라이드(스케일·피벗)를 보고 정한다.

## 타입 기본 8행 — 기존 프리팹에 코드만 추가 (같은 프리팹을 가리키는 두 번째 엔트리)

| type | assetCode | 프리팹 |
|---|---|---|
| SurveyKiosk | `BOOTH_KIOSK_SURVEY` | `Prefabs/Booth/SurveyKiosk.prefab` |
| ConsultationDesk | `BOOTH_DESK_CONSULT` | `ConsultationDesk.prefab` |
| VideoScreen | `BOOTH_SCREEN_VIDEO` | `VideoScreen.prefab` |
| ProjectPanel | `BOOTH_PANEL_PROJECT` | `ProjectPanel.prefab` |
| RecruitmentBoard | `BOOTH_BOARD_RECRUIT` | `RecruitmentBoard.prefab` |
| LikeVote | `BOOTH_STAND_LIKE` | `LikeVote.prefab` |
| Laptop | `BOOTH_DESK_LAPTOP` | `Laptop.prefab` |
| AiAgent | `BOOTH_AGENT_AI` | `AiAgent.prefab` |

## 레거시 교체 2행 — 코드만 바꾼다 (자산 동일)

| 현재 | 교체 | 프리팹 |
|---|---|---|
| `FURNITURE_DEFAULT` | `FURN_SET_TABLE_CHAIRS` (typeDefault) | `Furniture.prefab` (테이블 1 + 의자 3) |
| `DECORATION_DEFAULT` | `DISP_SET_BOX_01` (typeDefault) | `Decoration.prefab` (DisplayBox01Stuff 조립) |

## 신규 18행 — ExpoKit 프리팹 매핑

| type | assetCode | ExpoKit 프리팹 | 비고 |
|---|---|---|---|
| Furniture | `FURN_CHAIR_01_WHITE` | `Furniture/Chair01_white.prefab` | |
| Furniture | `FURN_CHAIR_01_BLUE` | `Furniture/Chair01_blue.prefab` | |
| Furniture | `FURN_CHAIR_01_ORANGE` | `Furniture/Chair01_orange.prefab` | Furniture.prefab 이 중첩으로 쓰는 것 |
| Furniture | `FURN_CHAIR_02_WHITE` | `Furniture/Chair02_White.prefab` | |
| Furniture | `FURN_COUNTER_01` | `Furniture/Counter01.prefab` | 천 + 상판 + 선반 |
| Furniture | `FURN_COUNTER_01B` | `Furniture/Counter01b.prefab` | |
| Furniture | `FURN_COUNTER_02` | `Furniture/Counter02.prefab` | |
| Furniture | `FURN_TABLE_ROUND` | `Furniture/TableRound.prefab` | |
| Furniture | `FURN_TABLE_SQUARE` | `Furniture/TableSquare.prefab` | |
| Decoration | `DISP_BOX_01` | **없음** — `Models/Stands/DisplayBox01.FBX` 단품 | 프리팹 신규 생성 필요(유리 + 흰 플라스틱 재질 확인) |
| Decoration | `DISP_STAND_PLASTIC_01` | `Displays/StandPlastic01.prefab` | |
| Decoration | `DEVICE_TABLET` | `Misc/Tablet.prefab` | `TabletCube` 는 제외 |
| Decoration | `STRUCT_PANEL_01` | `Panels/Panel01b.prefab` | |
| Decoration | `STRUCT_PANEL_02` | `Panels/Panel02b.prefab` | |
| Decoration | `STRUCT_PANEL_03` | `Panels/Panel03.prefab` | |
| Decoration | `STRUCT_TRUSS_BASE` | `Truss/TrussBase.prefab` | |
| Decoration | `STRUCT_TRUSS_VERTICAL` | `Truss/TrussVertical.prefab` | |
| Decoration | `STRUCT_TRUSS_HORIZONTAL_LAMP` | `Truss/TrussHorizontal_Lamp02.prefab` | 조명 포함 — WebGL 32등 상한(T-216) 영향 확인 |

## 검증 (에디터 플레이, Mock 레이아웃)

1. `Tools/mock-api/booth-slot-layout.json` 에 신규 코드로 오브젝트를 놓은 슬롯을 하나 추가 → 방에 그 물건이 뜬다(경고 `Unknown assetCode` 0건).
2. 각 프리팹의 발 위치·스케일(1 m = 13.26 u, 앵커 균등 스케일)이 계약 `OBJECT_LOCAL_BOUNDS` 와 맞는지 바운즈 출력으로 확인 — FE 매핑표(#146 산출물)와 대조.
3. 레거시 코드 2개(`FURNITURE_DEFAULT`·`DECORATION_DEFAULT`)는 **예약** — 저장된 layout 에 남아 있을 수 있어 경고 후 typeDefault 로 떨어지는 기존 경로를 유지한다(colosair 요청).
4. 조명이 들어간 `STRUCT_TRUSS_HORIZONTAL_LAMP` 는 부스당 최대 12개 규칙(헌법 22조)과 T-216 32등 상한을 함께 본다.
