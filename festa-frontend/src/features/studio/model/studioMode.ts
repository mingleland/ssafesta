// Booth Studio 모드 — 같은 Shell 안의 상태 전환이지 별도 화면이 아니다 (S15P21A604-405).
// layout = 배치(계약: Draft/Publish), facade = 외관(계약: PUT /facade 즉시 반영), template = 프리셋(목업).
export type StudioMode = 'layout' | 'facade' | 'template';

export const STUDIO_MODES: ReadonlyArray<{ id: StudioMode; label: string }> = [
  { id: 'layout', label: '구조' },
  { id: 'facade', label: '외관' },
  { id: 'template', label: '템플릿' },
];

export type TransformTool = 'select' | 'move' | 'rotate';
