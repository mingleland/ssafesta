// ObjectType 10종의 연결 요건·실물 크기 판정표 — FE 사전 경고(UX 보조)의 원본
// 공개 가부의 최종 판정은 항상 서버 errors/warnings다(FR-016, 헌법 16조).
// 출처: specs/005-booth-studio-layout/data-model.md §ObjectType 판정표, contracts/layout-api.md §10-1

import type { ObjectType } from './types';

export const OBJECT_TYPES: readonly ObjectType[] = [
  'AI_AGENT',
  'VIDEO_SCREEN',
  'PROJECT_PANEL',
  'SURVEY_KIOSK',
  'RECRUITMENT_BOARD',
  'CONSULTATION_DESK',
  'LAPTOP',
  'LIKE_VOTE',
  'FURNITURE',
  'DECORATION',
] as const;

export type ObjectCategory = 'FUNCTIONAL' | 'DECORATIVE' | 'UNDETERMINED';

export interface ObjectTypeInfo {
  category: ObjectCategory;
  // 연결 요건 미충족 시 사전 경고 대상인지 — configId 필드 유무 검사가 아니라 이 표가 판정 원본이다.
  // 기획 결정이 뒤집혀도(C-04) 서버가 errors/warnings 사이에서 옮길 뿐, 이 표는 "사전에 같은 경고를 보여줄지"만 결정한다.
  warnOnMissingConfig: boolean;
  // configId로 콘텐츠를 연결하는 타입인지 — 편집기가 연결 입력을 띄울지를 이 값으로 정한다.
  // 경고 여부(warnOnMissingConfig)와 별개다: 016의 LAPTOP처럼 "연결은 필요한데 configId로 하지 않는" 타입이 있다.
  linksConfigId: boolean;
}

export const OBJECT_TYPE_INFO: Record<ObjectType, ObjectTypeInfo> = {
  AI_AGENT: { category: 'FUNCTIONAL', warnOnMissingConfig: true, linksConfigId: true },
  VIDEO_SCREEN: { category: 'FUNCTIONAL', warnOnMissingConfig: true, linksConfigId: true },
  PROJECT_PANEL: { category: 'FUNCTIONAL', warnOnMissingConfig: true, linksConfigId: true },
  SURVEY_KIOSK: { category: 'FUNCTIONAL', warnOnMissingConfig: true, linksConfigId: true },
  RECRUITMENT_BOARD: { category: 'UNDETERMINED', warnOnMissingConfig: false, linksConfigId: true }, // 요건 자체가 spec Key Entities에 없음
  CONSULTATION_DESK: { category: 'FUNCTIONAL', warnOnMissingConfig: true, linksConfigId: true },
  // 016 확정(#97): 홈페이지 주소는 booths.homepage_url이 소유한다. configId로 연결하지 않으므로 입력을 띄우지 않고,
  // 미등록 경고의 근거도 configId 부재가 아니라 URL 미등록이라 서버가 판정한다(계약 §3-1). 보내면 CONFIG_UNVERIFIED가 붙는다.
  LAPTOP: { category: 'FUNCTIONAL', warnOnMissingConfig: false, linksConfigId: false },
  LIKE_VOTE: { category: 'UNDETERMINED', warnOnMissingConfig: false, linksConfigId: true }, // 부스 자체가 대상일 가능성 — 오브젝트별 연결 불명
  FURNITURE: { category: 'DECORATIVE', warnOnMissingConfig: false, linksConfigId: false }, // assetCode는 외형 선택이지 콘텐츠 연결이 아님
  DECORATION: { category: 'DECORATIVE', warnOnMissingConfig: false, linksConfigId: false },
};

export interface AABB {
  min: { x: number; y: number; z: number };
  max: { x: number; y: number; z: number };
}

// type → 로컬 AABB (rotationY=0 기준, 단위 m). 원점은 전 타입 바닥(min.y=0), x·z는 비대칭이다 —
// size만 들고 중앙 원점을 가정하면 회전 계산이 틀린다(계약서 경고, §10-1).
// 프리팹이 바뀌면 값도 바뀐다(계약 원본은 contracts/layout-api.md §10-1) — 사전 검증(Polish 단계)에서만 참조,
// 이 사본이 아니라 계약서가 갱신되면 이 표도 같이 갱신한다.
export const OBJECT_LOCAL_BOUNDS: Record<ObjectType, AABB> = {
  AI_AGENT: { min: { x: -0.31, y: 0, z: -0.16 }, max: { x: 0.31, y: 1.15, z: 0.16 } },
  VIDEO_SCREEN: { min: { x: -1.5, y: 0, z: -0.15 }, max: { x: 1.2, y: 2.1, z: 0.15 } },
  PROJECT_PANEL: { min: { x: -0.78, y: 0, z: -0.18 }, max: { x: 0.77, y: 2.72, z: 0.18 } },
  SURVEY_KIOSK: { min: { x: -0.31, y: 0, z: -0.16 }, max: { x: 0.31, y: 0.93, z: 0.16 } },
  RECRUITMENT_BOARD: { min: { x: -1.5, y: 0, z: -0.18 }, max: { x: 1.5, y: 2.72, z: 0.18 } },
  CONSULTATION_DESK: { min: { x: -0.93, y: 0, z: -1.0 }, max: { x: 0.93, y: 0.92, z: 0.16 } },
  LAPTOP: { min: { x: -0.4, y: 0, z: -0.4 }, max: { x: 0.4, y: 0.94, z: 0.4 } },
  LIKE_VOTE: { min: { x: -0.31, y: 0, z: -0.16 }, max: { x: 0.31, y: 1.23, z: 0.16 } },
  FURNITURE: { min: { x: -0.61, y: 0, z: -0.86 }, max: { x: 0.89, y: 0.75, z: 0.86 } },
  DECORATION: { min: { x: -0.3, y: 0, z: -0.3 }, max: { x: 0.3, y: 1.61, z: 0.3 } },
};
