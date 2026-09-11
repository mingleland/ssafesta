// 드래그 종류 판정 — Ctrl(⌘)+좌클릭은 툴과 무관하게 회전이다 (S15P21A604-603).
//
// 두 렌더러(R3F·SVG)가 이 함수 하나를 쓴다. 각자 판정하면 같은 조작이 캔버스마다 다르게
// 동작하고, 그건 사용자가 "왜 여기선 안 되지" 로 겪는다. 그래서 규칙을 여기서 고정한다.
import { describe, expect, it } from 'vitest';
import { dragKind } from '../../model/studioMode';

const none = { ctrlKey: false, metaKey: false };
const ctrl = { ctrlKey: true, metaKey: false };
const meta = { ctrlKey: false, metaKey: true };

describe('dragKind — 수식키가 툴을 이긴다', () => {
  it('수식키가 없으면 툴을 따른다', () => {
    expect(dragKind(none, 'select')).toBe('move');
    expect(dragKind(none, 'move')).toBe('move');
    expect(dragKind(none, 'rotate')).toBe('rotate');
  });

  it('Ctrl 이면 어떤 툴이든 회전이다', () => {
    expect(dragKind(ctrl, 'select')).toBe('rotate');
    expect(dragKind(ctrl, 'move')).toBe('rotate');
    expect(dragKind(ctrl, 'rotate')).toBe('rotate');
  });

  it('macOS 의 ⌘ 도 같다 — Ctrl 만 보면 맥에서 회전이 안 된다', () => {
    expect(dragKind(meta, 'select')).toBe('rotate');
    expect(dragKind(meta, 'move')).toBe('rotate');
  });

  it('회전 툴에서 수식키를 눌러도 이동으로 뒤집히지 않는다', () => {
    // 수식키는 "회전을 더한다" 이지 "토글" 이 아니다
    expect(dragKind(ctrl, 'rotate')).toBe('rotate');
  });
});
