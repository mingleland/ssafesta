// @vitest-environment jsdom
// 개발자 입장 실패가 **이유를 실제로 말하는지** (S15P21A604-467).
//
// 두 가지를 잠근다.
//   1. 실패는 공통 Toast 로 나간다 — 이 화면(LoginPage)이 이미 쓰는 층이다. 직접 만든
//      배너는 버튼을 가렸고, "알림을 레이아웃에서 떼어 놓는다" 는 toastStore 의 설계 이유를
//      정면으로 어긴 것이었다.
//   2. 메시지가 `[object Object]` 가 아니다. `api()` 는 `Error` 가 아니라 오류 봉투 객체를
//      던져서(`shared/api/client.ts` 의 `isApiError`) `String(error)` 로는 그렇게 찍힌다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../model/devEntry', async () => {
  const actual = await vi.importActual<typeof import('../../model/devEntry')>('../../model/devEntry');
  return { ...actual, IS_DEV_ENTRY: true, enterAsDeveloper: vi.fn() };
});
vi.mock('../../../../shared/ui/toast/toastStore', () => ({ showToast: vi.fn() }));

const { IS_DEV_ENTRY, enterAsDeveloper } = await import('../../model/devEntry');
const { showToast } = await import('../../../../shared/ui/toast/toastStore');
const { DevEntryButton } = await import('../../ui/DevEntryButton');

afterEach(cleanup);

describe('DevEntryButton 실패 표시', () => {
  it('오류 봉투를 error Toast 로 낸다 — code·message 가 들어가고 [object Object] 가 아니다', async () => {
    if (!IS_DEV_ENTRY) return; // 플래그가 꺼진 환경에서는 컴포넌트가 없다
    vi.mocked(enterAsDeveloper).mockRejectedValue({
      code: 'INVALID_MEMBER_TOKEN',
      message: '유효하지 않거나 만료된 로그인 세션입니다.',
      errors: [],
      warnings: [],
    });

    const { container } = render(
      <MemoryRouter>
        <DevEntryButton />
      </MemoryRouter>,
    );
    screen.getByLabelText('개발자로 입장').click();

    await waitFor(() => expect(showToast).toHaveBeenCalled());
    const [message, kind] = vi.mocked(showToast).mock.calls[0];
    expect(message).toContain('INVALID_MEMBER_TOKEN');
    expect(message).toContain('유효하지 않거나 만료된 로그인 세션입니다.');
    expect(message).not.toContain('[object Object]');
    expect(kind).toBe('error');

    // 컴포넌트가 자기 알림 DOM 을 만들지 않는다 — 알림은 Toast 층 소관이다
    expect(container.querySelector('[role="alert"]')).toBeNull();
  });
});
