# Booth 장식 에셋 매핑 공백 — 실측 조사

> 2026-09-07 · 조사자 이정헌(FE) · 게시 전 초안
> 근거는 전부 저장소 실측이다. 추정한 곳은 그렇게 적었다.

## 0. 한 줄

**부스 꾸미기 에셋에 대해 어휘가 네 벌 있고, 그중 어느 것도 정본이 아니다.** FE 팔레트가 쓰는 `assetCode` 7종은 저장소 어디에서도 정의되지 않고, 에셋 파이프라인이 만드는 4종과 **겹치는 것이 하나도 없다.**

## 1. 계층별 실측

### 1-1. Unity — `BoothObjectRegistry.asset`

`ObjectType` 번호 → prefab 11개. **`assetCode` 개념이 없다.** 타입 하나에 prefab 하나다.

| type | prefab |
|---|---|
| 1 | `_Project/Prefabs/Booth/AiAgent.prefab` |
| 2 | `VideoScreen.prefab` |
| 3 | `ProjectPanel.prefab` |
| 4 | `SurveyKiosk.prefab` |
| 5 | `RecruitmentBoard.prefab` |
| 6 | `ConsultationDesk.prefab` |
| 7 | `Laptop.prefab` |
| 8 | `LikeVote.prefab` |
| 9 | `Furniture.prefab` |
| 10 | `Decoration.prefab` |
| 11 | `_Project/Prefabs/World/ArcadeCabinet_GamePortal.prefab` |

### 1-2. 계약 — `layout-api.md` / `entities/layout/types.ts`

```ts
assetCode?: string;  // 장식형(FURNITURE·DECORATION) 외형 선택 코드
```

**자유 문자열이다.** 허용 목록도 형식 제약도 없다. 서버(`booth/LayoutJson.java:133`)도 `String assetCode` 로 받기만 하고 검증하지 않는다.

### 1-3. FE 팔레트 — `features/studio/model/visualAssets.ts`

15개 타일 중 7개에 `assetCode` 가 붙어 있다.

| id | 라벨 | objectType | assetCode |
|---|---|---|---|
| `wall-plain` | 기본 패널 | DECORATION | `WALL_PLAIN` |
| `counter-graphic` | 그래픽 카운터 | FURNITURE | `COUNTER_GRAPHIC` |
| `counter-shelf` | 진열 선반 | FURNITURE | `SHELF` |
| `truss-beam` | 트러스 빔 | DECORATION | `TRUSS_BEAM` (locked) |
| `truss-pillar` | 기둥 | DECORATION | `TRUSS_PILLAR` (locked) |
| `truss-gate` | 게이트 | DECORATION | `TRUSS_GATE` (locked) |
| `plant` | 화분 | DECORATION | `PLANT` |

나머지 8개(그래픽 패널·채용 보드·상담 데스크·노트북·영상 스크린·설문 키오스크·AI 직원·좋아요 스탠드)는 기능형이라 `assetCode` 가 없다 — 계약과 일치한다.

**이 7종의 정의처는 이 파일 하나뿐이다.** `specs/`·계약 문서·서버·파이프라인 어디에도 없다.

### 1-4. 에셋 파이프라인 — `tools/assets/booth-assets.config.mjs`

| assetCode | objectType | 원본 |
|---|---|---|
| `DECORATION_DEFAULT` | DECORATION | `Stands/DisplayBox01.FBX` |
| `FURNITURE_CHAIR01` | FURNITURE | `Furniture/Chair01.FBX` |
| `SURVEY_KIOSK_DEFAULT` | SURVEY_KIOSK | `SurveyKiosk.prefab` (조립체) |
| `FURNITURE_CHAIR02_WHITE` | FURNITURE | `Furniture/Chair02b.FBX` |

**팔레트 7종과 교집합 0.**

### 1-5. 서버 카탈로그 — `catalog_items`

```
item_type 별 집계:  AVATAR_PART 97
```

**부스 장식 항목이 0건이다.** 팔레트가 `findCatalogItem(catalog, { code: item.assetCode })` 로 조회하지만 매칭될 행이 없다. 그래서 `AssetPalette.tsx:44` 주석이 이렇게 적혀 있다 — *"카탈로그 매핑은 assetCode 기준 조회만 — 매핑 확정 전엔 레지스트리의 locked 표시로 대체"*.

### 1-6. 테스트·문서에 또 다른 철자

- `boothAssetManifest.test.ts:41` — `DECORATION_PLANT`
- `booth-studio-r3f-asset-pipeline-plan.md:930` — `COUNTER_GRAPHIC_01`

## 2. 결과적으로 지금 일어나는 일

- 팔레트에서 **그래픽 카운터**를 놓으면 `assetCode: 'COUNTER_GRAPHIC'` 이 저장된다. 파이프라인에 그 코드가 없으므로 `pickAsset` 이 코드 조회에 실패하고, `objectType: FURNITURE` 의 타입 기본을 찾는데 그것도 없어(`FURNITURE_CHAIR02_WHITE.typeDefault === false`) **파라메트릭 박스로 떨어진다.**
- 즉 **현재 팔레트 15종 중 실제 모델이 뜨는 것은 설문 키오스크 1종뿐이다.**
- 서버는 어떤 문자열이든 받으므로, 오탈자나 폐기된 코드가 저장돼도 **게시 시점까지 아무도 모른다.**

## 3. 진행 방식 — 결정을 먼저 받지 않는다

접근을 바꿨다. **게임 파트에 "부스용이 무엇인지" 목록만 받고, 검증은 FE 가 여기서 한다.**
받은 것을 전량 파이프라인에 태워 "되는 것 / 안 되는 것 + 이유" 표를 만들어 돌려주고,
어휘 정본·잠금 정책 같은 결정은 **그 표가 나온 뒤에** 붙인다. 지금 결정을 물으면
근거 없이 답해야 하기 때문이다.

파일 자체는 이미 저장소에 있다 — `Art/Booth` 233개, `Prefabs/Booth` 11개,
`ScriptableObjects` 108개를 FE 가 읽고 있다. 그래서 요청은 **전송이 아니라 판정**이다.

### 받을 것

1. 부스 꾸미기에 쓰는(쓸) 오브젝트 전체 — prefab/FBX 경로 + 한 줄 설명
2. 장식형(`FURNITURE`·`DECORATION`) 외형 후보, 그리고 **타입당 prefab 1개인 현재
   구조를 Unity 가 어떻게 확장할 계획인지**
3. `ExpoKit`(22 FBX) / `CarnivalKit`(42 FBX) 중 부스 범위
4. 저장소에 아직 없는 것이 있으면 그 목록

### FE 가 돌려줄 것

항목별로 — 변환 가부 · 실측 AABB 대 계약 축별 오차(mm) · 텍스처 3채널 도달 여부 ·
tri/바이트 · 실패 시 이유.

## 4. 결정이 필요하지만 지금은 묻지 않는 것

표가 나온 뒤에 올린다.

1. **`assetCode` 어휘의 정본은 누구인가** — 기획 문서 / Unity 레지스트리 / 서버 카탈로그.
   지금은 FE 파일이 사실상 정본 노릇을 하고 있는데 FE 가 정할 사안이 아니다
2. **트러스 3종의 잠금·가격 정책** — 팔레트에 `locked: true` 인데 카탈로그에 항목이 없다

## 5. FE 의 현재 추정 (확정 아님 — 목록을 받으면 버린다)

이름만 보고 짐작한 것이다. 이 표를 근거로 쓰지 않는다.

| 팔레트 assetCode | 추정 원본 (ExpoKit) | 확실도 |
|---|---|---|
| `WALL_PLAIN` | `Models/Panels/Panel01b.FBX` | 낮음 — Panel 3종 중 어느 것인지 불명 |
| `COUNTER_GRAPHIC` | `Models/Furniture/Counter02.FBX` 또는 `Counter01Cloth.FBX` | 낮음 |
| `SHELF` | `Models/Furniture/CounterShelf.FBX` | 중간 — 이름 일치 |
| `TRUSS_BEAM` | `Models/Truss/TrussHorizontal.FBX` | 중간 |
| `TRUSS_PILLAR` | `Models/Truss/TrussVertical.FBX` | 중간 |
| `TRUSS_GATE` | `Models/Truss/TrussBase.FBX` | 낮음 |
| `PLANT` | **ExpoKit 에 없음** — CarnivalKit 쪽을 봐야 한다 | 없음 |

## 6. 확보 가능한 원본 풀 (실측)

`festa-unity/Assets/_Project/Art/Booth` 아래 FBX **64개**.

| 팩 / 카테고리 | 개수 | 파일 |
|---|---|---|
| ExpoKit / Furniture | 11 | Chair01, Chair02b, Counter01, Counter01Cloth, Counter01Cloth01, Counter01Top, Counter02, Counter02Cloth, CounterShelf, TableRound, TableSquare |
| ExpoKit / Truss | 3 | TrussBase, TrussHorizontal, TrussVertical |
| ExpoKit / Panels | 3 | Panel01b, Panel02b, Panel03 |
| ExpoKit / Stands | 2 | DisplayBox01, StandPlastic01 |
| ExpoKit / Misc · Lamps · Floors | 3 | Tablet, Lamp02b, Floor01 |
| CarnivalKit / Models | 42 | (월드 장식용으로 보인다 — 부스 범위 여부 확인 필요) |

Booth prefab 11개는 §1-1 표와 같다.

## 7. 제약

- **벤더 라이선스 게이트** — `ExpoKit` 은 `REVIEW_REQUIRED` 라 산출물이 로컬 Spike 범위를 못 벗어난다(`compiler/licenseGate.mjs`). 매핑을 확정해도 **배포 반입은 라이선스 판정 뒤다**
- Runtime Asset Compiler v1 은 `TARGET_ASSET_CODES` 2종으로 의도적으로 제한돼 있다(`compile-runtime-assets.mjs:19`). 매핑이 늘면 이 목록도 함께 열어야 한다
