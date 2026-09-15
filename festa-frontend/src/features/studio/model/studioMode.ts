// Booth Studio 모드 — 같은 Shell 안의 상태 전환이지 별도 화면이 아니다 (S15P21A604-405).
// layout = 배치(계약: Draft/Publish), facade = 외관(계약: PUT /facade 즉시 반영), template = 프리셋(목업).
export type StudioMode = 'layout' | 'facade' | 'template';

export const STUDIO_MODES: ReadonlyArray<{ id: StudioMode; label: string }> = [
  { id: 'layout', label: '구조' },
  { id: 'facade', label: '외관' },
  { id: 'template', label: '템플릿' },
];

export type TransformTool = 'select' | 'move' | 'rotate';

/**
 * 드래그가 이동인지 회전인지 (S15P21A604-603).
 *
 * **Ctrl(⌘) + 좌클릭은 툴과 무관하게 회전이다.** 예전에는 툴 모드 토글만 봐서, 회전하려면
 * 매번 도구를 바꿨다가 되돌려야 했다. 툴바 버튼은 그대로 둔다 — 터치·키보드 사용자의 경로다.
 *
 * 두 렌더러(R3F·SVG)가 같은 규칙을 써야 해서 판정을 여기 한 곳에 둔다. 각자 두면 조작감이 갈린다.
 */
export function dragKind(
  modifiers: { ctrlKey: boolean; metaKey: boolean },
  tool: TransformTool,
): 'move' | 'rotate' {
  if (modifiers.ctrlKey || modifiers.metaKey) return 'rotate';
  return tool === 'rotate' ? 'rotate' : 'move';
}

/**
 * 드래그로 인정하는 최소 이동(화면 픽셀) — 이 거리를 넘기 전에는 좌표를 커밋하지 않는다 (S15P21A604-689).
 *
 * 미터가 아니라 픽셀로 재는 이유는 사람의 손떨림이 화면에서 일정하기 때문이다. 미터로 재면 같은 흔들림이
 * 줌 배율에 따라 통과했다 막혔다 한다. 5px 는 OS 들이 클릭과 드래그를 가르는 값(Windows 4, macOS 5)과 같은 자리다.
 */
export const DRAG_THRESHOLD_PX = 5;

/**
 * pointerdown 지점에서 그만큼 움직였는가. 한 번 넘으면 그 드래그는 계속 커밋한다(호출부가 기억한다) —
 * 매 프레임 다시 재면 임계값 경계에서 커밋이 끊겼다 이어지며 좌표가 튄다.
 */
export function exceedsDragThreshold(
  start: { x: number; y: number },
  now: { x: number; y: number },
  thresholdPx: number = DRAG_THRESHOLD_PX,
): boolean {
  return Math.hypot(now.x - start.x, now.y - start.y) >= thresholdPx;
}
