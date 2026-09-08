// 오브젝트 10종의 기본 색·이름·표면 성질 — 두 렌더러가 같은 표를 본다.
// 전에는 이 표가 TemporaryIsoRenderer 안에만 있어서 두 번째 렌더러가 색을 다시 적어야 했다.
// 색이 갈라지면 "SVG 대비 개선" 을 눈으로 비교할 때 무엇이 렌더러 차이인지 알 수 없다.
import type { ObjectType } from '../../../../entities/layout/types';
import type { AABB } from '../../../../entities/layout/objectTypes';

export const OBJECT_FILL: Record<ObjectType, string> = {
  AI_AGENT: '#8fb8ff',
  VIDEO_SCREEN: '#28324a',
  PROJECT_PANEL: '#eef2fa',
  SURVEY_KIOSK: '#cfd6e6',
  RECRUITMENT_BOARD: '#f6f7fb',
  CONSULTATION_DESK: '#f2f4f8',
  LAPTOP: '#dfe4ee',
  LIKE_VOTE: '#ffc4dc',
  FURNITURE: '#e6e9f0',
  DECORATION: '#79c88a',
};

export const OBJECT_LABEL: Record<ObjectType, string> = {
  AI_AGENT: 'AI 직원',
  VIDEO_SCREEN: '영상 스크린',
  PROJECT_PANEL: '그래픽 패널',
  SURVEY_KIOSK: '설문 키오스크',
  RECRUITMENT_BOARD: '채용 보드',
  CONSULTATION_DESK: '상담 데스크',
  LAPTOP: '노트북',
  LIKE_VOTE: '좋아요 스탠드',
  FURNITURE: '가구',
  DECORATION: '장식',
};

/** 타입 표에 없는 값이 와도 편집기가 죽지 않게 하는 최소 상자 (SC-005 — 미지 타입에도 나머지는 동작한다) */
export const FALLBACK_BOX: AABB = { min: { x: -0.25, y: 0, z: -0.25 }, max: { x: 0.25, y: 1, z: 0.25 } };

/**
 * PBR 표면 성질. 시안의 "재질 — 무광(Matte)/유광" 축을 renderer 가 실제로 표현할 수 있는 값으로 옮긴 것이다.
 * SVG 는 이 값을 쓰지 않는다(면별 명도만 있다) — 그 차이가 곧 이번 Spike 가 재는 것이다.
 */
export interface Surface {
  roughness: number;
  metalness: number;
  /** 0 이면 불투명. 스크린·유리 계열만 값을 갖는다 */
  opacity: number;
}

const MATTE: Surface = { roughness: 0.85, metalness: 0, opacity: 1 };
const SATIN: Surface = { roughness: 0.55, metalness: 0.05, opacity: 1 };
const METAL: Surface = { roughness: 0.35, metalness: 0.6, opacity: 1 };
const SCREEN: Surface = { roughness: 0.18, metalness: 0.2, opacity: 1 };

export const OBJECT_SURFACE: Record<ObjectType, Surface> = {
  AI_AGENT: MATTE,
  VIDEO_SCREEN: SCREEN,
  PROJECT_PANEL: SATIN,
  SURVEY_KIOSK: SATIN,
  RECRUITMENT_BOARD: SATIN,
  CONSULTATION_DESK: SATIN,
  LAPTOP: METAL,
  LIKE_VOTE: MATTE,
  FURNITURE: SATIN,
  DECORATION: MATTE,
};

/** hex 를 밝기 배율로 흔든다 — SVG 의 면별 명도와 R3F 의 벽 측면이 같은 함수를 쓴다 */
export function shade(hex: string, amount: number): string {
  const n = parseInt(hex.slice(1), 16);
  const f = (v: number) => Math.max(0, Math.min(255, Math.round(v * amount)));
  return 'rgb(' + f((n >> 16) & 255) + ',' + f((n >> 8) & 255) + ',' + f(n & 255) + ')';
}
