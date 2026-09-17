/// <reference types="node" />
// 전역 선택·포인터 정책 회귀 (S15P21A604-464).
//
// CSS 규칙이라 컴포넌트 테스트로는 잡히지 않는다 — jsdom 은 링크된 스타일시트를 적용하지
// 않고 user-select 를 계산하지도 않는다. 그래서 규칙 자체가 index.css 에 남아 있는지를
// 확인한다. 선택을 여는 자리를 하나라도 지우면 여기서 실패한다.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../../../index.css'),
  'utf8',
);

/** 선언 블록에서 공백을 지운 형태로 규칙 하나를 꺼낸다 */
function blockFor(selectorList: string): string {
  const start = css.indexOf(selectorList);
  expect(start, `선택자를 찾지 못했다: ${selectorList}`).toBeGreaterThan(-1);
  const open = css.indexOf('{', start);
  const close = css.indexOf('}', open);
  return css.slice(open + 1, close).replace(/\s/g, '');
}

describe('전역 선택 차단', () => {
  it('body 에서 선택을 끈다', () => {
    const body = blockFor('body {');
    expect(body).toContain('user-select:none');
    expect(body).toContain('-webkit-user-select:none');
  });

  it('이미지 드래그를 CSS 에서도 끈다', () => {
    expect(blockFor('img {')).toContain('-webkit-user-drag:none');
  });
});

describe('다시 여는 자리 — 하나라도 빠지면 사용자가 글자를 다룰 수 없다', () => {
  const openers = [
    'input',
    'textarea',
    'select',
    "[contenteditable]:not([contenteditable='false'])",
    "[role='alert']",
    '.selectable',
  ];

  it.each(openers)('%s 은 선택할 수 있다', (selector) => {
    expect(css).toContain(selector);
  });

  it('그 목록이 하나의 규칙으로 묶여 text 를 되돌린다', () => {
    const block = blockFor('.selectable {');
    expect(block).toContain('user-select:text');
    expect(block).toContain('-webkit-user-select:text');
  });
});

// Unity 는 `Cursor.visible` 이 바뀔 때마다 캔버스에 `style.cursor` 를 인라인으로 쓴다.
// `!important` 가 빠지면 캔버스 위에서만 브랜드 포인터가 조용히 사라진다.
describe('브랜드 포인터', () => {
  it('body 가 커스텀 포인터를 깐다', () => {
    const body = blockFor('body {');
    expect(body).toContain("cursor:url('/cursors/festa-pointer.png')72,auto");
  });

  it('Unity 캔버스는 인라인 커서를 이기도록 !important 로 되돌린다', () => {
    const canvas = blockFor('#unity-canvas {');
    expect(canvas).toContain("cursor:url('/cursors/festa-pointer.png')72,auto!important");
    expect(canvas).toContain('image-set(');
    expect(canvas.match(/!important/g)).toHaveLength(2);
  });

  // 비활성 버튼 위에서만 시스템 화살표로 돌아가던 자리(2026-09-17). 화면마다 적어 둔
  // pointer·default·not-allowed 를 하나하나 쫓지 않고 전역 규칙으로 덮는다.
  it('전역 규칙 하나가 모든 요소를 덮는다 — 비활성도 예외가 아니다', () => {
    const all = blockFor(':where(*) {');
    expect(all).toContain("cursor:url('/cursors/festa-pointer.png')72,auto!important");
    expect(all).toContain('image-set(');
    expect(css).not.toContain('button:not(:disabled)');
  });

  it('모양이 곧 사용법인 자리만 되돌린다 — 입력칸의 I빔과 끌기 손잡이', () => {
    expect(css).toContain('cursor: text !important');
    expect(css).toContain('cursor: col-resize !important');
    expect(css).toContain('cursor: grabbing !important');
  });
});
