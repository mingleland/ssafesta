// @vitest-environment jsdom
// 이미지 유령 드래그는 막고, Game Studio 의 실제 drag&drop 은 건드리지 않는다.
import { afterEach, describe, expect, it } from 'vitest';
import { installImageDragGuard, shouldPreventDrag } from '../../imageDragGuard';

afterEach(() => {
  document.body.innerHTML = '';
});

function mount(html: string): HTMLElement {
  document.body.innerHTML = html;
  return document.body.firstElementChild as HTMLElement;
}

describe('shouldPreventDrag', () => {
  it('맨 이미지는 막는다', () => {
    expect(shouldPreventDrag(mount('<img alt="" src="x.png" />'))).toBe(true);
  });

  it('draggable 을 명시한 요소 안의 이미지는 통과시킨다 — 그쪽은 기능이다', () => {
    const root = mount('<div draggable="true"><img alt="" src="x.png" /></div>');
    expect(shouldPreventDrag(root.querySelector('img'))).toBe(false);
  });

  it('draggable 요소 자신도 통과시킨다', () => {
    expect(shouldPreventDrag(mount('<div draggable="true">팔레트 항목</div>'))).toBe(false);
  });

  it('이미지가 아닌 것은 애초에 대상이 아니다', () => {
    expect(shouldPreventDrag(mount('<p>텍스트</p>'))).toBe(false);
    expect(shouldPreventDrag(null)).toBe(false);
  });
});

describe('installImageDragGuard', () => {
  it('문서 드래그를 가로채고, 해제하면 원래대로 돌아온다', () => {
    const img = mount('<img alt="" src="x.png" />');
    const uninstall = installImageDragGuard();

    const blocked = new Event('dragstart', { bubbles: true, cancelable: true });
    img.dispatchEvent(blocked);
    expect(blocked.defaultPrevented).toBe(true);

    uninstall();
    const allowed = new Event('dragstart', { bubbles: true, cancelable: true });
    img.dispatchEvent(allowed);
    expect(allowed.defaultPrevented).toBe(false);
  });
});
