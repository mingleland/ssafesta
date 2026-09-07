// @vitest-environment jsdom
// 개발자 입장구 렌더 규칙 (S15P21A604-467).
//
// IS_DEV_ENTRY 는 빌드 시 확정돼 테스트에서 갈아끼울 수 없다. 그래서 `.env.local` 값에 따라
// 결과가 갈리지 않도록 **양쪽 모두 성립하는 성질**만 잠근다 — 꺼져 있으면 흔적이 없고,
// 켜져 있으면 제품 패널 밖의 fixed 요소로만 존재한다(제품 기하를 밀지 않는다).
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DevEntryButton } from '../../ui/DevEntryButton';
import { IS_DEV_ENTRY } from '../../model/devEntry';

afterEach(cleanup);

function renderButton() {
  return render(
    <MemoryRouter>
      <DevEntryButton />
    </MemoryRouter>,
  );
}

describe('DevEntryButton', () => {
  it('플래그가 꺼져 있으면 아무 것도 렌더하지 않는다', () => {
    if (IS_DEV_ENTRY) return; // 켜진 환경에서는 아래 케이스가 대신 검증한다
    expect(renderButton().container.firstChild).toBeNull();
  });

  it('켜져 있으면 이름 있는 버튼 하나만 낸다 — 제품 로그인 버튼을 만들지 않는다', () => {
    if (!IS_DEV_ENTRY) return;
    const { container } = renderButton();
    const buttons = container.querySelectorAll('button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0].getAttribute('aria-label')).toBe('개발자로 입장');
    expect(container.querySelector('.login-btn')).toBeNull();
  });
});
