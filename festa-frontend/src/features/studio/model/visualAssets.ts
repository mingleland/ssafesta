// Booth Studio 시각 자산 레지스트리 — 썸네일·라벨·프리셋을 JSX 에 흩뿌리지 않는다 (S15P21A604-405).
// 지금은 CSS 로 그리는 썸네일 종류(thumb)만 든다. 실제 에셋/GLB 썸네일이 오면 이 표의 thumb 만 교체한다.
// objectType 은 계약(entities/layout/types) 10종 그대로 — 새 타입을 발명하지 않는다.
import type { ObjectType } from '../../../entities/layout/types';
import type { ThemeCode } from '../../../entities/booth/types';

export type ThumbKind = 'panel' | 'panel-graphic' | 'panel-glass' | 'counter' | 'counter-graphic' | 'shelf' | 'truss' | 'truss-pillar' | 'truss-gate' | 'kiosk' | 'plant' | 'laptop' | 'screen' | 'agent' | 'desk' | 'board' | 'vote' | 'chair';

export interface PaletteItem {
  id: string;
  label: string;
  objectType: ObjectType;
  thumb: ThumbKind;
  /**
   * 장식형 외형 코드(계약 assetCode). 기능형은 없음.
   *
   * **정본은 Unity `BoothObjectRegistry.asset` 이다** (S15P21A604-509, GitLab #146·#154).
   * 여기 적은 코드가 그 레지스트리에 없으면 월드에서 타입 기본 자산(상자)으로 떨어진다 —
   * 예전에 `WALL_PLAIN` 등 7종이 그 상태였고, 사용자는 "패널을 놓았는데 상자가 나온다" 를 겪었다.
   * 그 사고가 다시 나지 않도록 `tools/paletteAssetCodes.test.mjs` 가 레지스트리와 대조한다.
   */
  assetCode?: string;
  /** 카탈로그 매핑 확정 전 표시용 — 실제 잠금은 CatalogItemVM 이 정본 */
  locked?: boolean;
}

export interface PaletteSection {
  id: string;
  title: string;
  items: PaletteItem[];
}

// ── 기능 오브젝트 — assetCode 가 없는 8종. 팔레트에 **항상** 선다 (S15P21A604-783, GitLab #201) ──
//
// **왜 장식형과 갈라 두는가.** 실물 자산 목록은 runtime manifest 가 만드는데
// (`assetLibrary.buildLibrary`) manifest 에는 assetCode 가 붙은 자산만 들어간다. 그래서 기능형은
// 그 목록에 **구조적으로 들어올 수 없다** — 서버가 이 8종을 콘텐츠 연결로 다루지 외형 코드로
// 다루지 않기 때문이다(`OBJECT_TYPE_INFO` 의 FUNCTIONAL).
//
// 예전에는 "실물이 있으면 실물만, 없으면 이 목록만" 이라는 **배타 분기**였다. `-741` 이 runtime
// asset 을 자동 생성하게 만들면서 실물이 항상 존재하게 됐고, 그 순간 기능형 8종이 통째로 화면에서
// 사라졌다 — AI 직원을 놓을 진입점 자체가 없어졌다(#201 실측). 배타를 걷고 **기능형은 항상,
// 장식형만 실물 유무로 교체**한다.
export const FUNCTIONAL_PALETTE: PaletteSection = {
  id: 'functional',
  title: '기능 오브젝트',
  items: [
    { id: 'agent', label: 'AI 직원', objectType: 'AI_AGENT', thumb: 'agent' },
    { id: 'wall-graphic', label: '그래픽 패널', objectType: 'PROJECT_PANEL', thumb: 'panel-graphic' },
    { id: 'kiosk', label: '설문 키오스크', objectType: 'SURVEY_KIOSK', thumb: 'kiosk' },
    { id: 'counter-desk', label: '상담 데스크', objectType: 'CONSULTATION_DESK', thumb: 'desk' },
    { id: 'screen', label: '영상 스크린', objectType: 'VIDEO_SCREEN', thumb: 'screen' },
    { id: 'vote', label: '좋아요 스탠드', objectType: 'LIKE_VOTE', thumb: 'vote' },
    { id: 'wall-board', label: '채용 보드', objectType: 'RECRUITMENT_BOARD', thumb: 'board' },
    { id: 'laptop', label: '노트북', objectType: 'LAPTOP', thumb: 'laptop' },
  ],
};

// ── 장식(Layout) 팔레트 — assetCode 가 붙은 외형 7종. manifest 가 실물을 주면 그쪽으로 교체된다 ──
// 여기에 assetCode 없는 항목을 다시 넣지 마라. 넣으면 실물이 있는 환경에서 또 사라진다(#201).
export const LAYOUT_PALETTE: PaletteSection[] = [
  {
    id: 'wall',
    title: '벽면 패널',
    items: [
      { id: 'wall-plain', label: '기본 패널', objectType: 'DECORATION', thumb: 'panel', assetCode: 'STRUCT_PANEL_01' },
    ],
  },
  {
    id: 'counter',
    title: '카운터',
    items: [
      { id: 'counter-graphic', label: '그래픽 카운터', objectType: 'FURNITURE', thumb: 'counter-graphic', assetCode: 'FURN_COUNTER_02' },
      { id: 'counter-shelf', label: '진열 선반', objectType: 'FURNITURE', thumb: 'shelf', assetCode: 'DISP_STAND_PLASTIC_01' },
    ],
  },
  {
    id: 'truss',
    title: '트러스 / 프레임',
    items: [
      { id: 'truss-beam', label: '트러스 빔', objectType: 'DECORATION', thumb: 'truss', assetCode: 'STRUCT_TRUSS_HORIZONTAL_LAMP', locked: true },
      { id: 'truss-pillar', label: '기둥', objectType: 'DECORATION', thumb: 'truss-pillar', assetCode: 'STRUCT_TRUSS_VERTICAL', locked: true },
      { id: 'truss-gate', label: '게이트', objectType: 'DECORATION', thumb: 'truss-gate', assetCode: 'STRUCT_TRUSS_BASE', locked: true },
    ],
  },
  {
    id: 'props',
    title: '소품',
    items: [
      { id: 'plant', label: '화분', objectType: 'DECORATION', thumb: 'plant', assetCode: 'DECOR_PLANT_01' },
    ],
  },
];

// ── 템플릿(Template) 모드 프리셋. 저장되는 것은 themeCode·primaryColor 뿐이고(외관 모드에서 저장),
//    floorHex 는 캔버스 미리보기 입력이다 — 화면에는 보이지만 계약에는 없다 (S15P21A604-617).
export interface TemplatePreset {
  id: string;
  label: string;
  themeCode: ThemeCode;
  primaryHex: string; // FACADE_PALETTE 12색 중 하나 — 계약 밖 색 금지
  floorHex: string; // 캔버스 미리보기 바닥색 — 저장되지 않는다
  description: string;
}
export const TEMPLATE_PRESETS: TemplatePreset[] = [
  { id: 'blue', label: 'Blue', themeCode: 'SSAFY_BLUE', primaryHex: '#3B82F6', floorHex: '#1d4ed8', description: '시안 그래픽 · 블루 카펫 · 화이트 월' },
  { id: 'green', label: 'Green', themeCode: 'DEFAULT', primaryHex: '#22C55E', floorHex: '#166534', description: '라임 포인트 · 그린 카펫' },
  { id: 'orange', label: 'Orange', themeCode: 'WARM', primaryHex: '#F97316', floorHex: '#9a3412', description: '웜 톤 · 앰버 포인트' },
  { id: 'custom', label: 'Custom', themeCode: 'MONO', primaryHex: '#6366F1', floorHex: '#312e81', description: '외관 모드에서 직접 조합' },
];
