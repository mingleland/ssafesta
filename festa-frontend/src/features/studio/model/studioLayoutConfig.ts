// Booth Studio Shell 의 패널 치수 — 한 곳에서만 바꾼다 (S15P21A604-405).
// 값은 booth-2_5d-reference.png 실측 비율(1672px 폭 기준)에서 뽑았다. 새 Reference 가 오면 여기만 고친다.
import type { CSSProperties } from 'react';

export const STUDIO_LAYOUT = {
  toolbarHeight: 56,
  modeRailWidth: 108,
  paletteWidth: 212,
  inspectorWidth: 232,
  bottomBarHeight: 52,
  panelGap: 10,
  shellInset: 5, // 월드가 보이는 여백(vh/vw 단위, %)
} as const;

export function layoutConfigStyle(): CSSProperties {
  const l = STUDIO_LAYOUT;
  return {
    '--studio-toolbar-h': `${l.toolbarHeight}px`,
    '--studio-rail-w': `${l.modeRailWidth}px`,
    '--studio-palette-w': `${l.paletteWidth}px`,
    '--studio-inspector-w': `${l.inspectorWidth}px`,
    '--studio-bottom-h': `${l.bottomBarHeight}px`,
    '--studio-gap': `${l.panelGap}px`,
    '--studio-inset': `${l.shellInset}%`,
  } as CSSProperties;
}
