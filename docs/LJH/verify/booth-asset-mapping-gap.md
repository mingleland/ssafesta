# Booth 장식 에셋 매핑 공백 — 실측 조사

> 2026-09-07 작성 · 2026-09-08 게임 파트 회신(#146) 반영 · **2026-09-10 정합 완료(§6)** · 조사자 이정헌(FE)
> 근거는 전부 저장소 실측이다. 추정한 곳은 그렇게 적었다.
> **틀린 문장은 지우지 않고 §0-1 에 남긴다** — 지우면 다음 사람이 같은 함정을 다시 판다.

## 0. 한 줄

**정본은 Unity `BoothObjectRegistry.asset` 하나로 정해졌고, FE 팔레트·파이프라인이 그것과 1:1 로 붙었다.**
네 벌이던 어휘 중 FE 쪽 두 벌(팔레트 7종·파이프라인 4종)이 정본 코드로 맞춰졌고, 어긋나면 CI 가 red 다
(`tools/paletteAssetCodes.test.mjs`). 남은 것은 `PLANT` 하나 — 자산은 있는데 정본 코드가 없다(§6-3).

조사 시점(2026-09-07)의 한 줄은 이랬다: *"어휘가 네 벌 있고 그중 어느 것도 정본이 아니다. FE 팔레트가 쓰는
`assetCode` 7종은 저장소 어디에서도 정의되지 않고, 파이프라인이 만드는 4종과 겹치는 것이 하나도 없다."*

## 0-1. 회신 대조 (#146, 2026-09-08) — 서로 하나씩 틀렸다

### 내가 틀린 것

| 내 문장 | 실제 |
|---|---|
| §1-1 *"`assetCode` 개념이 없다. 타입 하나에 prefab 하나다"* | **틀렸다.** `BoothObjectRegistry.cs:35` 에 `assetCode` 필드가 있고 `GetPrefab(type, assetCode)` 가 `type:assetCode` 2단 조회를 한다(`:45`·`:103`). 못 찾으면 경고 후 타입 기본으로 떨어진다(`:55`) |
| §1-1 (같은 문장의 전제) | `.asset` 11 엔트리 중 **2개는 이미 채워져 있다** — type 9 `FURNITURE_DEFAULT`, type 10 `DECORATION_DEFAULT`. "전부 비어 있다" 도 사실이 아니다 |

**결과가 바뀐다.** FE 는 Unity 구조 변경을 기다릴 필요가 없다. 레지스트리에 `type:assetCode` 엔트리를 채우는 **데이터 작업**만 남는다. 계약의 `assetCode` 자유 문자열을 그대로 키로 쓴다.

### 회신이 틀린 것

회신은 *"부스 오브젝트 11종이 전부 프리미티브 플레이스홀더"* · *"`AiAgent`·`VideoScreen`·`ProjectPanel`·`SurveyKiosk`·… 참조 FBX 없음(전부)"* 이라고 적었다. **`develop` `cf636cd3` 기준 실측은 다르다.**

원인은 조사 깊이다 — 이 prefab 들은 **FBX 를 직접 참조하지 않고 중첩 prefab 을 참조한다.** `.FBX` guid 만 찾으면 0건으로 보인다.

| prefab | 직접 참조(중첩 prefab) |
|---|---|
| `AiAgent` | `31_Cashier.prefab` + Animator controller |
| `VideoScreen` | `TrussVertical.prefab` · `Panel01b.prefab` |
| `ProjectPanel` | `TrussBase.prefab` · `Panel03.prefab` |
| `SurveyKiosk` | `Tablet.prefab` · `Counter01.prefab` |
| `RecruitmentBoard` | `Panel02b.prefab` · `TrussBase.prefab` |
| `ConsultationDesk` | `Counter01b.prefab` · `Chair02_White.prefab` · `Counter01.prefab` |
| `Laptop` | `TableSquare.prefab` · `laptop.prefab` |
| `LikeVote` | `Counter02.prefab` · `StandPlastic01.prefab` |
| `Furniture` | `Chair01_orange` · `Chair01_blue` · `Chair01_white` · `TableRound.prefab` |
| `Decoration` | `DisplayBox01Stuff.prefab` |

`Counter01.prefab` 을 한 단계 더 들어가면 `Counter01.FBX` · `Counter01Top.FBX` · `Counter01Cloth01.FBX` · `CounterShelf.FBX` · `Frame.fbx` 가 나온다.

그리고 `CreatePlaceholder` 는 **`prefab == null` 일 때만 도는 fallback** 이다(`BoothObjectFactory.cs:39-41`). 레지스트리 11 엔트리 전부 prefab guid 가 실재하므로 정상 경로에서는 프리미티브가 안 뜬다.

FE 파이프라인이 키오스크에서 뽑아낸 모델은 다른 경로가 아니라 **바로 그 `SurveyKiosk.prefab`** 이다. `-476` 이 중첩 prefab 해석을 만들어 둔 것이 이 차이다.

### 회신에서 그대로 받는 것

- **ExpoKit 22 FBX 가 부스용 전부**, 현재 사용 중은 `Floor01`·`Panel02b` 2개
- **CarnivalKit 42 FBX 는 월드 축제존 전용** → 부스 검증 범위에서 제외. `Art/World/CarnivalKit` 으로 이동 예정
- **화분(`PLANT`) 원본은 저장소에 없다**
- **게이트(`TRUSS_GATE`) 단일 모델이 없다** — 기둥 2 + 빔 1 조합이어야 한다. `TrussBase` 는 받침이다
- `SHELF` → `CounterShelf`, `TRUSS_BEAM` → `TrussHorizontal`, `TRUSS_PILLAR` → `TrussVertical` **추정이 맞았다**
- 저장소에 없는 부스용 prefab·FBX 는 **없다**
- 라이선스 게이트는 양쪽 공통 제약 — 별도 이슈로 뺀다

### 남은 결정 — FE 가 정한다

`COUNTER_GRAPHIC` 에 어느 카운터를 붙일지. 카운터 계열이 6종(`Counter01`·`01Cloth`·`01Cloth01`·`01Top`·`02`·`02Cloth`)인데 "그래픽" 텍스처 변형은 없다. 게임 파트가 *"FE 팔레트 어휘를 정본으로 삼겠다"* 고 했으므로 팔레트 코드가 그대로 레지스트리 키가 된다.

## 1. 계층별 실측

### 1-1. Unity — `BoothObjectRegistry.asset`

`ObjectType` 번호 → prefab 11개.

> ~~**`assetCode` 개념이 없다.** 타입 하나에 prefab 하나다.~~ — **이 문장은 틀렸다**(§0-1). `GetPrefab(type, assetCode)` 2단 조회가 이미 있고, 11 엔트리 중 2개는 `assetCode` 가 채워져 있다. `.asset` 만 보고 `.cs` 를 안 읽어서 난 오류다.

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
  (이 문장은 **FE Booth Studio 2.5D 렌더러** 기준이다 — 런타임 manifest 에 구운 자산이 2종뿐이라 그렇다. Unity 런타임 기준이 아니다. #146 회신이 "Unity 쪽에서는 0종" 이라고 대조했는데 서로 다른 층을 말한 것이다.)
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

## 7. 제약 — 2026-09-10 기준 최신

- **벤더 라이선스 게이트는 열렸다.** `source-packs.lock.json` 의 `ExpoKit` 이 `runtimeCompilePolicy:
  ALLOW_RUNTIME_COMPILE` 이고, `evidence.decision` 에 *"runtime 변환·웹 사용·production emission 을 사용자
  판단으로 PASS (2026-09-08)"* 가 적혀 있다. 게임 파트 회신(#148)은 출처·약관 종류 확인까지였고 배포 허용
  판정은 사용자가 내렸다. `notVerified` 에 남은 것은 구매 계정·주문번호·취득일·seat 범위다.
  **따라서 파이프라인 확대를 막는 라이선스 blocker 는 없다** — 이 문서가 오래 "REVIEW_REQUIRED" 라 적어 온
  자리가 여기다.
- `TARGET_ASSET_CODES` 는 이제 10종이다(`compile-runtime-assets.mjs`). 팔레트가 참조하는 코드는 전부 포함돼
  있고, 그 1:1 은 `tools/paletteAssetCodes.test.mjs` 2층이 지킨다.

---

## 6. 정합 결과 (2026-09-10, S15P21A604-509)

### 6-1. Unity 정본이 실제로 서빙본에 들어갔는가 — **커밋 SHA 로는 판정할 수 없었다**

`#146` 회신이 커밋 `7fb057d1`·`5f680097` 을 댔지만 **그 SHA 는 어느 WebGL 빌드의 조상도 아니고 develop
조상도 아니다**(rebase/squash 된 것으로 보인다). `git merge-base --is-ancestor` 로 "포함 안 됨" 이 나오는데
실제로는 들어가 있다 — SHA 로 판정했으면 새 빌드를 요청하는 오판을 했을 것이다.

**파일 내용으로 판정한 것이 답이다.**

| package | build commit | 시각 | 값 있는 `assetCode` |
|---:|---|---|---:|
| 107 | `490bde34` | 09-10 09:03 | 37 |
| 106 | `6e69c0b6` | 09-09 17:42 | 37 |
| 105 `e6e60774` (서빙 중) | | 09-09 14:05 | **37** |
| 104 | `664bc74e` | 09-09 10:42 | 30 |
| 100 | `e30d8d49` | 09-08 15:17 | 2 |

37 = canonical 28 + 레거시 별칭 7 + 예약 2(`FURNITURE_DEFAULT`·`DECORATION_DEFAULT`).
`664bc74e`(30) → `e6e60774`(37) 의 차이가 정확히 레거시 별칭 7종이고, 그 커밋 제목이 *"부스 게시본 실측
후속(**별칭**·404 재시도)"* 다.

**결론: 28행 등록은 현재 서빙본에 이미 들어 있다. 새 Unity 빌드가 필요 없다.**

### 6-2. 28행 판정 — 누락 0 · 중복 0 · code mutation 0

레지스트리 엔트리 48 = 값 있음 37 + 빈 코드(typeDefault) 11. 중복 0, 비정규 표기 0.
회신의 *"표의 값 그대로이고 중간 변환은 없습니다"* 가 실측으로 확인된다.

**레거시 별칭 7종은 정본과 같은 prefab guid 를 가리킨다** — 매핑에 판단이 들어간 곳이 없다는 뜻이고,
`#154` 가 제시한 대응표가 그대로 맞다.

| 레거시 별칭 | 가리키는 prefab | 대응 정본 |
|---|---|---|
| `WALL_PLAIN` | `Decor/STRUCT_PANEL_01.prefab` | `STRUCT_PANEL_01` |
| `COUNTER_GRAPHIC` | `Decor/FURN_COUNTER_02.prefab` | `FURN_COUNTER_02` |
| `SHELF` | `Decor/DISP_STAND_PLASTIC_01.prefab` | `DISP_STAND_PLASTIC_01` |
| `TRUSS_BEAM` | `Decor/STRUCT_TRUSS_HORIZONTAL_LAMP.prefab` | `STRUCT_TRUSS_HORIZONTAL_LAMP` |
| `TRUSS_PILLAR` | `Decor/STRUCT_TRUSS_VERTICAL.prefab` | `STRUCT_TRUSS_VERTICAL` |
| `TRUSS_GATE` | `Decor/STRUCT_TRUSS_BASE.prefab` | `STRUCT_TRUSS_BASE` |
| `PLANT` | `Decor/PLANT.prefab` | **없음** |

### 6-3. `PLANT` — 자산은 있는데 정본 코드가 없다

게임 파트가 저폴리 화분 래퍼를 신설해 `Decor/PLANT.prefab` 이 실재한다. 그런데 정본 28행에 화분 대응
코드가 없다. 팔레트는 `PLANT` 를 유지하고 **canonical 신규 정의를 `#154` 에 요청**했다 — 정본 표 갱신은
게임 파트 결정이다. 그때까지 별칭으로 렌더되므로 사용자에게 보이는 문제는 없다.

### 6-4. 파이프라인 — 4종 → 10종

팔레트가 참조하는 6종을 `booth-assets.config.mjs` 에 넣고 실제로 변환했다. 래퍼가 PrefabInstance 라
축은 prefab 이 세우고(`yUp`), 재질은 래퍼가 URP 기본으로 덮어 지정하지 않는다.

| assetCode | GLB | tri | bbox (m) |
|---|---:|---:|---|
| `STRUCT_PANEL_01` | 16,828 B | 148 | 2.00 × 2.10 × 0.02 |
| `FURN_COUNTER_02` | 29,516 B | — | 0.62 × 0.92 × 0.32 |
| `DISP_STAND_PLASTIC_01` | 5,148 B | — | 0.21 × 0.31 × 0.09 |
| `STRUCT_TRUSS_HORIZONTAL_LAMP` | 43,088 B | 982 | 1.56 × 2.25 × 0.30 |
| `STRUCT_TRUSS_VERTICAL` | 26,500 B | 576 | 0.30 × 2.00 × 0.30 |
| `STRUCT_TRUSS_BASE` | 19,308 B | 366 | 0.35 × 0.35 × 0.50 |

경고 8건은 전부 `MonoBehaviour 는 재현하지 않는다`(래퍼가 붙인 BoxCollider)다 — 기존 `SurveyKiosk` 도
같은 경고 2건을 내므로 신규 결함이 아니다. **막힌 항목은 없다.**

dev 서버 실측: `/assets/booth-runtime/manifest.json` 이 10 assets 를 내고 6종 `.glb`·`.webp` 가 전부 200 이다.

### 6-5. 알려진 어긋남 — 피벗 (이 작업 범위 밖)

변환된 GLB 는 `bounds.min.y = 0` 으로 **바닥 정규화**된다. 그런데 Unity 원본은 `STRUCT_TRUSS_VERTICAL` 의
피벗이 바닥 +0.5 m(TrussBase 위에 올리는 전제), `STRUCT_TRUSS_HORIZONTAL_LAMP` 가 2.6 m 높이다(#146 특기).
**스튜디오 미리보기와 월드가 그만큼 어긋난다.**

이번 범위에서 고치지 않았다 — 미리보기에 그 전제를 반영하려면 manifest 계약에 피벗 오프셋 필드를 더해야
하고, 그것은 계약 변경이라 `-509` 완료조건 밖이다. 별도로 남긴다.

### 6-6. 월드 렌더 실증은 HOLD

FE 가 새 코드로 게시해 월드에서 확인하려면 **회원 세션**이 필요한데, 실 BE 에서 개발자 진입이 거부된다
(`enterAsDeveloper()` 의 `'dev-entry'` 표식 토큰 — LJH T-71 계열). 게스트는 스튜디오에 들어갈 수 없고,
로컬 게시본은 슬롯 1 의 `SURVEY_KIOSK`(assetCode 없음) 하나뿐이라 레거시 코드로 저장된 대상도 없다.

**Unity 측 렌더는 게임 파트 실측이 있다** — Mock 픽스처에 `FURN_CHAIR_01_BLUE`·`STRUCT_PANEL_01` 을 넣어
바닥 피벗으로 서는 것과 `Unknown assetCode` 경고 0건을 확인했다고 회신했다(#146). 그것은 그쪽 증거이고
**내 증거가 아니다** — 그래서 여기서는 HOLD 로 적는다.
