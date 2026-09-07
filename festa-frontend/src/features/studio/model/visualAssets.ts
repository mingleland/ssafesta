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
  /** 장식형 외형 코드(계약 assetCode). 기능형은 없음 */
  assetCode?: string;
  /** 카탈로그 매핑 확정 전 표시용 — 실제 잠금은 CatalogItemVM 이 정본 */
  locked?: boolean;
}

export interface PaletteSection {
  id: string;
  title: string;
  items: PaletteItem[];
}

// ── 구조(Layout) 모드 팔레트 — Reference 의 벽면 패널 / 카운터 / 트러스·프레임 / 소품 + 기능 오브젝트 ──
export const LAYOUT_PALETTE: PaletteSection[] = [
  {
    id: 'wall',
    title: '벽면 패널',
    items: [
      { id: 'wall-plain', label: '기본 패널', objectType: 'DECORATION', thumb: 'panel', assetCode: 'WALL_PLAIN' },
      { id: 'wall-graphic', label: '그래픽 패널', objectType: 'PROJECT_PANEL', thumb: 'panel-graphic' },
      { id: 'wall-board', label: '채용 보드', objectType: 'RECRUITMENT_BOARD', thumb: 'board' },
    ],
  },
  {
    id: 'counter',
    title: '카운터',
    items: [
      { id: 'counter-desk', label: '상담 데스크', objectType: 'CONSULTATION_DESK', thumb: 'desk' },
      { id: 'counter-graphic', label: '그래픽 카운터', objectType: 'FURNITURE', thumb: 'counter-graphic', assetCode: 'COUNTER_GRAPHIC' },
      { id: 'counter-shelf', label: '진열 선반', objectType: 'FURNITURE', thumb: 'shelf', assetCode: 'SHELF' },
    ],
  },
  {
    id: 'truss',
    title: '트러스 / 프레임',
    items: [
      { id: 'truss-beam', label: '트러스 빔', objectType: 'DECORATION', thumb: 'truss', assetCode: 'TRUSS_BEAM', locked: true },
      { id: 'truss-pillar', label: '기둥', objectType: 'DECORATION', thumb: 'truss-pillar', assetCode: 'TRUSS_PILLAR', locked: true },
      { id: 'truss-gate', label: '게이트', objectType: 'DECORATION', thumb: 'truss-gate', assetCode: 'TRUSS_GATE', locked: true },
    ],
  },
  {
    id: 'device',
    title: '전자기기',
    items: [
      { id: 'laptop', label: '노트북', objectType: 'LAPTOP', thumb: 'laptop' },
      { id: 'screen', label: '영상 스크린', objectType: 'VIDEO_SCREEN', thumb: 'screen' },
      { id: 'kiosk', label: '설문 키오스크', objectType: 'SURVEY_KIOSK', thumb: 'kiosk' },
    ],
  },
  {
    id: 'props',
    title: '소품',
    items: [
      { id: 'agent', label: 'AI 직원', objectType: 'AI_AGENT', thumb: 'agent' },
      { id: 'vote', label: '좋아요 스탠드', objectType: 'LIKE_VOTE', thumb: 'vote' },
      { id: 'plant', label: '화분', objectType: 'DECORATION', thumb: 'plant', assetCode: 'PLANT' },
    ],
  },
];

// ── 외관(Facade) 모드 팔레트 — 실제 계약은 themeCode·primaryColor·signText·logoUrl 4필드뿐.
//    벽면/바닥/그래픽 항목은 목업 표현(PROVISIONAL)이며 저장되지 않는다.
export interface FacadeSwatch {
  id: string;
  label: string;
  provisional: boolean;
}
export const FACADE_PALETTE_SECTIONS: Array<{ id: string; title: string; items: FacadeSwatch[] }> = [
  { id: 'wall', title: '벽면', items: [{ id: 'wall-white', label: '화이트', provisional: true }, { id: 'wall-graphic', label: '그래픽', provisional: true }, { id: 'wall-wood', label: '우드', provisional: true }] },
  { id: 'floor', title: '바닥', items: [{ id: 'floor-blue', label: '블루 카펫', provisional: true }, { id: 'floor-grey', label: '그레이', provisional: true }, { id: 'floor-wood', label: '우드', provisional: true }] },
  { id: 'graphic', title: '그래픽', items: [{ id: 'g-wave', label: '웨이브', provisional: true }, { id: 'g-solid', label: '단색', provisional: true }, { id: 'g-logo', label: '로고', provisional: true }] },
];

// ── 템플릿(Template) 모드 프리셋 — 목업. 실제 계약으로 저장 가능한 부분은 themeCode·primaryColor 뿐(외관 모드에서 저장).
export interface TemplatePreset {
  id: string;
  label: string;
  themeCode: ThemeCode;
  primaryHex: string; // FACADE_PALETTE 12색 중 하나 — 계약 밖 색 금지
  accentHex: string; // 목업 전용(트러스 포인트·카운터 그래픽) — 저장 안 됨
  floorHex: string; // 목업 전용
  description: string;
}
export const TEMPLATE_PRESETS: TemplatePreset[] = [
  { id: 'blue', label: 'Blue', themeCode: 'SSAFY_BLUE', primaryHex: '#3B82F6', accentHex: '#06B6D4', floorHex: '#1d4ed8', description: '시안 그래픽 · 블루 카펫 · 화이트 월' },
  { id: 'green', label: 'Green', themeCode: 'DEFAULT', primaryHex: '#22C55E', accentHex: '#84CC16', floorHex: '#166534', description: '라임 포인트 · 그린 카펫' },
  { id: 'orange', label: 'Orange', themeCode: 'WARM', primaryHex: '#F97316', accentHex: '#F59E0B', floorHex: '#9a3412', description: '웜 톤 · 앰버 포인트' },
  { id: 'custom', label: 'Custom', themeCode: 'MONO', primaryHex: '#6366F1', accentHex: '#A855F7', floorHex: '#312e81', description: '외관 모드에서 직접 조합' },
];
