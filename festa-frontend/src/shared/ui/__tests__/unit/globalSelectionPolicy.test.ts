/// <reference types="node" />
// 전역 선택 정책 회귀 (S15P21A604-464).
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
