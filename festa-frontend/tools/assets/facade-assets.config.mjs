// Facade 18종 — 부스 외관 완성형 (S15P21A604-552).
//
// 내부 Booth 자산과 **판정 기준이 다르다.** 내부는 사용자가 밀리미터 단위로 놓고 돌리는
// 편집 대상이라 형상 정확도가 계약이지만, Facade 는 "이 외관으로 하실래요?" 를 고르는
// 선택 UI 다. 알아볼 수 있으면 된다 — 그래서 공격적으로 줄인다.
//
// **1 prefab = 1 assetCode = 1 GLB = 1 thumbnail.** 부품(22)·소품(8)에는 코드를 주지
// 않는다. 완성형 내부 구성일 뿐이라 사용자가 개별로 고를 대상이 아니다.
//
// 정본: docs/LJH/ui-design/04_prototypes/booth-studio-2_5d-canonical-design.md §8-6

/**
 * cm 에서 m. CarnivalKit FBX 의 단위다.
 *
 * FBX 헤더 `UnitScaleFactor` 로 확정했다 — CarnivalKit = 1.0(1 unit = 1 cm), ExpoKit = 2.54
 * (1 unit = 1 inch, 그래서 저쪽은 INCH_TO_M). Unity `.meta` 는 양쪽 다 `useFileScale: 1` 이라
 * import 설정만 봐서는 갈리지 않는다.
 */
export const CM_TO_M = 0.01;

/** CarnivalKit prefab 뿌리 — 내부 자산의 UNITY_PREFAB_ROOT 와 다른 트리다 */
export const CARNIVAL_PREFAB_ROOT = '../festa-unity/Assets/_Project/Art/Booth/CarnivalKit/Prefabs';

/** License Gate 판정 단위 — source-packs.lock.json 의 키 */
export const FACADE_SOURCE_PACKAGE = 'CarnivalKit';

/**
 * @typedef {object} FacadeSource
 * @property {string} assetCode  `FACADE_<FAMILY>_<VARIANT>` — 정본 §8-6
 * @property {string} prefab     CARNIVAL_PREFAB_ROOT 기준 상대 경로
 * @property {string} group      UI 그룹 (게임 부스 / 독립 부스 / 노점 / 카트)
 * @property {string} displayName 사용자에게 보이는 이름
 * @property {'KEEP'|'FIX'|'PROP_ONLY'} [triage] 선별 판정 (§12-A-8). 없으면 KEEP
 * @property {string} [triageNote] FIX·PROP_ONLY 인 이유
 */

/** @type {FacadeSource[]} */
export const FACADE_ASSETS = [
  // ── 게임 부스 6 — PF_Combined (nested prefab 조립) ──────────────
  { assetCode: 'FACADE_BOOTH_RING_TOSS', prefab: 'PF_Combined/PF_Ring_Toss_Booth.prefab', group: 'game', displayName: '고리 던지기' },
  { assetCode: 'FACADE_BOOTH_WATER_GUN', prefab: 'PF_Combined/PF_Water_Gun_Booth.prefab', group: 'game', displayName: '물총 사격' },
  { assetCode: 'FACADE_BOOTH_CAN_KNOCKDOWN', prefab: 'PF_Combined/PF_Can_Knockdown_Booth.prefab', group: 'game', displayName: '캔 맞히기' },
  { assetCode: 'FACADE_BOOTH_DUCK_POND', prefab: 'PF_Combined/PF_Duck_Pond_Booth_Frame_V1.prefab', group: 'game', displayName: '오리 건지기' },
  { assetCode: 'FACADE_BOOTH_BALLOON_DART', prefab: 'PF_Combined/PF_Balloon_Board.prefab', group: 'game', displayName: '풍선 다트' },
  { assetCode: 'FACADE_BOOTH_BASKETBALL', prefab: 'PF_Combined/PF_Basketball_V1.prefab', group: 'game', displayName: '농구 게임' },

  // ── 독립 부스 4 ────────────────────────────────────────────────
  { assetCode: 'FACADE_BOOTH_FORTUNE_TELLER', prefab: 'PF_Fortune_Teller_Booth.prefab', group: 'booth', displayName: '점집' },
  { assetCode: 'FACADE_BOOTH_TICKET', prefab: 'PF_Small_Ticket_Booth.prefab', group: 'booth', displayName: '매표소' },
  {
    assetCode: 'FACADE_BOOTH_PRIZE_WALL',
    prefab: 'PF_Plush_Prize_Wall.prefab',
    group: 'booth',
    displayName: '인형 경품대',
    triage: 'PROP_ONLY',
    triageNote: '구성이 인형 15개뿐이고 벽·선반이 없다. 최대 부품이 전체의 20% — 외관 단위가 아니다',
  },
  { assetCode: 'FACADE_BOOTH_HIGH_STRIKER', prefab: 'PF_High_Striker_Bell_Tower_Game_V1.prefab', group: 'booth', displayName: '힘 측정기' },

  // ── 노점 3 ─────────────────────────────────────────────────────
  { assetCode: 'FACADE_STAND_CANDY_APPLE', prefab: 'PF_Candy_Apple_and_Sweets_Stand.prefab', group: 'stand', displayName: '사탕 노점' },
  { assetCode: 'FACADE_STAND_FUNNEL_CAKE', prefab: 'PF_Funnel_Cake_Stand.prefab', group: 'stand', displayName: '퍼넬 케이크' },
  { assetCode: 'FACADE_STAND_LEMONADE', prefab: 'PF_Lemonade_Drink_Stand.prefab', group: 'stand', displayName: '레모네이드' },

  // ── 카트 5 ─────────────────────────────────────────────────────
  { assetCode: 'FACADE_CART_COTTON_CANDY', prefab: 'PF_Cotton_Candy_Cart.prefab', group: 'cart', displayName: '솜사탕 카트' },
  { assetCode: 'FACADE_CART_POPCORN', prefab: 'PF_Popcorn_Cart.prefab', group: 'cart', displayName: '팝콘 카트' },
  {
    assetCode: 'FACADE_CART_HOT_DOG',
    prefab: 'PF_Hot_Dog_Cart.prefab',
    group: 'cart',
    displayName: '핫도그 카트',
    triage: 'FIX',
    triageNote: '카트 본체가 원점에서 10.04 m 떨어져 있다(2 덩어리). 나머지 17종은 1 덩어리',
  },
  { assetCode: 'FACADE_CART_ICE_CREAM', prefab: 'PF_Ice_Cream_Cart.prefab', group: 'cart', displayName: '아이스크림 카트' },
  { assetCode: 'FACADE_CART_SHAVED_ICE', prefab: 'PF_Shaved_Ice_Cart.prefab', group: 'cart', displayName: '빙수 카트' },
];

/**
 * 실제로 사용자에게 낼 수 있는 것 — `triage` 가 붙지 않은 것들.
 *
 * 18종을 그대로 확정하지 않는다(§12-A-8). 걸린 2종은 자산 authoring 문제라 **억지로
 * 살리지 않고** 판정만 붙여 둔다 — 원본을 고칠지 목록에서 뺄지는 별개 결정이다.
 */
export const FACADE_PROVIDABLE = FACADE_ASSETS.filter((a) => a.triage === undefined);

export const FACADE_GROUP_LABEL = {
  game: '게임 부스',
  booth: '독립 부스',
  stand: '노점',
  cart: '카트',
};
