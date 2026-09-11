// 반입 대상 목록 — Unity 원본 FBX ↔ 계약 assetCode (S15P21A604-473).
//
// 여기 있는 것만 변환한다. 전 타입 일괄 변환은 금지다 — 라이선스 확인 전이고, 파이프라인이
// 맞는지부터 소수로 판정해야 한다.
//
// 원본은 BoothObjectRegistry.asset(타입 10종 + GamePortal → prefab 11개)이 정본이다.
// 이 표는 그 중 "단일 메시로 끝나는" 것만 골라 낸 부분집합이다. Counter01·SurveyKiosk 처럼
// prefab 조립체인 것은 여기 없다 — 조립은 별도 단계다.

/** 인치 → 미터. ExpoKit FBX 의 단위다(DisplayBox01 실측으로 확인, 아래 주석 참조) */
export const INCH_TO_M = 0.0254;

export const UNITY_ASSET_ROOT = '../festa-unity/Assets/_Project/Art/Booth/ExpoKit/Models';

/** guid 역인덱스를 만들 뿌리. prefab 이 참조를 guid 로만 갖고 있어 전 트리를 훑어야 한다 */
export const UNITY_ASSETS_ROOT = '../festa-unity/Assets';

/** 조립체 prefab 의 뿌리 — BoothObjectRegistry 가 타입에 물려 둔 그 파일들이다 */
export const UNITY_PREFAB_ROOT = '../festa-unity/Assets/_Project/Prefabs/Booth';

/**
 * @typedef {object} BoothAssetSource
 * @property {string} assetCode   계약의 assetCode. LayoutObject.assetCode 와 같은 값이다
 * @property {string} objectType  계약 ObjectType — 도메인 AABB 를 어디서 가져올지 정한다
 * @property {'fbx'|'prefab'} kind 단일 메시(fbx)인가 조립체(prefab)인가
 * @property {string} [fbx]       kind='fbx' — UNITY_ASSET_ROOT 기준 상대 경로
 * @property {string} [prefab]    kind='prefab' — UNITY_PREFAB_ROOT 기준 상대 경로
 * @property {number} unitScale   원본 단위 → 미터
 * @property {'zUp'|'yUp'} upAxis 원본의 위 방향
 * @property {boolean} typeDefault assetCode 없이 그 타입으로 놓였을 때 쓸 자산인가
 * @property {string} sourcePackage License Gate 판정 단위 — source-packs.lock.json 의 키
 * @property {string} [material]   Unity `.mat` 경로 (UNITY_ASSETS_ROOT 기준). 없으면 색만 쓴다
 * @property {string} note        왜 이걸 골랐는지
 */

/** @type {BoothAssetSource[]} */
export const BOOTH_ASSETS = [
  {
    assetCode: 'DISP_BOX_01',
    objectType: 'DECORATION',
    kind: 'fbx',
    fbx: 'Stands/DisplayBox01.FBX',
    unitScale: INCH_TO_M,
    upAxis: 'zUp',
    typeDefault: true,
    sourcePackage: 'ExpoKit',
    material: '_Project/Art/Booth/ExpoKit/Textures/Surfaces/Materials/PlasticWhite.mat',
    // 원본 bbox ±11.811 × ±11.811 × 63.327 에 0.0254 를 곱하면 ±0.3 × ±0.3 × 1.608 이 되고,
    // 이는 계약 OBJECT_LOCAL_BOUNDS.DECORATION(±0.3, max.y 1.61)과 그대로 맞는다.
    // 스케일·축·피벗이 맞는지를 숫자로 판정할 수 있는 유일한 후보라 대표로 넣었다.
    note: '계약 AABB 와 1:1 로 대조 가능 — 파이프라인 정확도의 기준점',
  },
  {
    assetCode: 'FURN_CHAIR_01_WHITE',
    objectType: 'FURNITURE',
    kind: 'fbx',
    fbx: 'Furniture/Chair01.FBX',
    unitScale: INCH_TO_M,
    upAxis: 'zUp',
    // Unity 레지스트리의 FURNITURE 기본은 Furniture.prefab(조립체)이고 의자 단품이 아니다.
    // 그 조립 경로가 아직 없어 이번 검증 동안만 기본 자리를 대신 채운다 — 조립이 들어오면 false 로 내린다.
    typeDefault: true,
    sourcePackage: 'ExpoKit',
    material: '_Project/Art/Booth/ExpoKit/Textures/Surfaces/Materials/PlasticWhite.mat',
    // 주의: FURNITURE 타입의 기본 자산은 Furniture.prefab(테이블 1 + 의자 3의 조립체)이라
    // 계약 AABB(1.5 × 0.75 × 1.72)는 그 세트의 것이다. 의자 하나는 당연히 그보다 작다 —
    // 이 어긋남은 결함이 아니라 "assetCode 가 타입 기본과 다르다" 는 뜻이다.
    note: '의자 단품 — 타입 기본(조립체)과 다른 assetCode 라 계약 AABB 보다 작은 것이 정상',
  },
  {
    assetCode: 'BOOTH_KIOSK_SURVEY',
    objectType: 'SURVEY_KIOSK',
    kind: 'prefab',
    prefab: 'SurveyKiosk.prefab',
    unitScale: INCH_TO_M,
    // prefab 이 축을 세운다 — 자식 Transform 에 -90° X 회전이 이미 들어 있다.
    // 그래서 여기서 또 눕히면 두 번 돌아간다. 단일 FBX 와 다른 점이 이것 하나다.
    upAxis: 'yUp',
    typeDefault: true,
    sourcePackage: 'ExpoKit',
    // 이 prefab 이 실제로 참조하는 재질이다 — 가장 널리 쓰이는 일반 재질 대표이기도 하다
    material: '_Project/Art/Booth/ExpoKit/Textures/Surfaces/Materials/PlasticWhite.mat',
    // BoothObjectRegistry 의 SurveyKiosk = Counter01.prefab + Tablet.prefab 조립체다.
    // Counter01 은 다시 4개 FBX(본체·상판·천·선반)를 물고 있다 — 중첩까지 한 번에 검증된다.
    note: 'Unity prefab 계층(중첩 포함)을 FE 에서 재현할 수 있는지 보는 대표',
  },
  {
    assetCode: 'FURN_CHAIR_02_WHITE',
    objectType: 'FURNITURE',
    kind: 'fbx',
    fbx: 'Furniture/Chair02b.FBX',
    unitScale: INCH_TO_M,
    upAxis: 'zUp',
    // 타입 기본이 아니다 — Chair02b 재질/텍스처를 실제로 쓰는 자산을 fixture 로 들여온 것뿐이다.
    // Unity 에서 이 mesh 를 쓰는 것은 Chair02_White.prefab 이고 재질이 Chair02b.mat 다.
    typeDefault: false,
    sourcePackage: 'ExpoKit',
    material: '_Project/Art/Booth/ExpoKit/Textures/Furniture/Materials/Chair02b.mat',
    note: 'Chair02b 텍스처 세트(baseColor·normal·metallicRoughness)를 실제로 쓰는 자산 — 임의 매핑이 아니다',
  },
  // ── #154 팔레트 정합으로 들어온 6종 (S15P21A604-509) ─────────────────────────────
  // 팔레트가 내보내는 코드가 파이프라인 산출물과 1:1 이어야 한다는 것이 -509 완료조건 ② 다.
  // 게임 파트가 `Prefabs/Booth/Decor/<코드>.prefab` 로 ExpoKit 프리팹을 감싼 래퍼를 만들어 뒀고
  // (벤더 원본 무수정, GitLab #146), 레지스트리가 그 파일을 가리킨다 — 같은 파일을 여기서도 읽는다.
  //
  // 래퍼는 PrefabInstance 다: 루트 회전은 0 이고 원본 prefab 을 참조해 자식 Transform 을 override 한다.
  // 그래서 축은 prefab 이 세운다(`yUp`) — 단일 FBX 처럼 여기서 또 눕히면 두 번 돌아간다.
  // 재질은 래퍼가 URP 기본으로 덮어 뒀으므로 여기서 지정하지 않는다(색만 쓴다).
  {
    assetCode: 'STRUCT_PANEL_01',
    objectType: 'DECORATION',
    kind: 'prefab',
    prefab: 'Decor/STRUCT_PANEL_01.prefab',
    unitScale: INCH_TO_M,
    upAxis: 'yUp',
    typeDefault: false,
    sourcePackage: 'ExpoKit',
    note: '팔레트 벽면 패널 › 기본 패널. 예전 WALL_PLAIN 이 가리키던 그 자산이다 (#154)',
  },
  {
    assetCode: 'FURN_COUNTER_02',
    objectType: 'FURNITURE',
    kind: 'prefab',
    prefab: 'Decor/FURN_COUNTER_02.prefab',
    unitScale: INCH_TO_M,
    upAxis: 'yUp',
    typeDefault: false,
    sourcePackage: 'ExpoKit',
    note: '팔레트 카운터 › 그래픽 카운터. 예전 COUNTER_GRAPHIC (#154)',
  },
  {
    assetCode: 'DISP_STAND_PLASTIC_01',
    objectType: 'DECORATION',
    kind: 'prefab',
    prefab: 'Decor/DISP_STAND_PLASTIC_01.prefab',
    unitScale: INCH_TO_M,
    upAxis: 'yUp',
    typeDefault: false,
    sourcePackage: 'ExpoKit',
    note: '팔레트 카운터 › 진열 선반. 예전 SHELF (#154)',
  },
  {
    assetCode: 'STRUCT_TRUSS_HORIZONTAL_LAMP',
    objectType: 'DECORATION',
    kind: 'prefab',
    prefab: 'Decor/STRUCT_TRUSS_HORIZONTAL_LAMP.prefab',
    unitScale: INCH_TO_M,
    upAxis: 'yUp',
    typeDefault: false,
    sourcePackage: 'ExpoKit',
    // 원본 피벗이 2.6 m 높이에 있다(게임 파트 #146 특기). 스튜디오 미리보기가 바닥 피벗을
    // 가정하면 월드와 어긋난다 — 그 판정은 -509 P2 에 기록한다.
    note: '팔레트 트러스 › 트러스 빔. 예전 TRUSS_BEAM. 피벗이 바닥이 아니다 (#146)',
  },
  {
    assetCode: 'STRUCT_TRUSS_VERTICAL',
    objectType: 'DECORATION',
    kind: 'prefab',
    prefab: 'Decor/STRUCT_TRUSS_VERTICAL.prefab',
    unitScale: INCH_TO_M,
    upAxis: 'yUp',
    typeDefault: false,
    sourcePackage: 'ExpoKit',
    // 원본 피벗이 바닥에서 0.5 m 위다 — TrussBase 위에 올리는 전제 (#146).
    note: '팔레트 트러스 › 기둥. 예전 TRUSS_PILLAR. 피벗 바닥 +0.5 m (#146)',
  },
  {
    assetCode: 'STRUCT_TRUSS_BASE',
    objectType: 'DECORATION',
    kind: 'prefab',
    prefab: 'Decor/STRUCT_TRUSS_BASE.prefab',
    unitScale: INCH_TO_M,
    upAxis: 'yUp',
    typeDefault: false,
    sourcePackage: 'ExpoKit',
    note: '팔레트 트러스 › 게이트. 예전 TRUSS_GATE (#154)',
  },
];

/**
 * 재질 대표 — geometry 와 별개로 재질/텍스처 파이프라인만 재는 표본이다.
 *
 * 어느 asset 에 붙었는지와 무관하게 "이 재질이 런타임으로 넘어올 때 무엇이 되는가" 를 본다.
 * Chair02b 를 억지로 어떤 asset 의 재질로 갖다 붙이지 않는다 — Chair01 은 그 재질을 안 쓴다.
 */
export const MATERIAL_PROBES = [
  {
    id: 'PlasticWhite',
    sourcePackage: 'ExpoKit',
    material: '_Project/Art/Booth/ExpoKit/Textures/Surfaces/Materials/PlasticWhite.mat',
    note: '가장 널리 쓰이는 일반 재질. SurveyKiosk·Counter 계열이 이것을 쓴다',
  },
  {
    id: 'Chair02b',
    sourcePackage: 'ExpoKit',
    material: '_Project/Art/Booth/ExpoKit/Textures/Furniture/Materials/Chair02b.mat',
    note: 'normal map 을 포함한 최악 케이스 — 텍스처 세트 원본이 약 10.9MB 다',
  },
];