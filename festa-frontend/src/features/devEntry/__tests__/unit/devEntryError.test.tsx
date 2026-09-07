// @vitest-environment jsdom
// 개발자 입장 실패 배너가 **이유를 실제로 말하는지** (S15P21A604-467).
//
// 배너를 넣은 이유가 "조용한 실패" 를 없애는 것이었는데, 첫 구현이 `String(error)` 로 떨어뜨려
// 화면에 `[object Object]` 를 찍었다 — 이유를 드러내려고 만든 자리가 이유를 가렸다.
// `api()` 는 `Error` 가 아니라 오류 봉투 객체를 던진다(`shared/api/client.ts` 의 `isApiError`).
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../../model/devEntry', async () => {
  const actual = await vi.importActual<typeof import('../../model/devEntry')>('../../model/devEntry');
  return { ...actual, IS_DEV_ENTRY: true, enterAsDeveloper: vi.fn() };
});

const { IS_DEV_ENTRY, enterAsDeveloper } = await import('../../model/devEntry');
const { DevEntryButton } = await import('../../ui/DevEntryButton');

afterEach(cleanup);

describe('DevEntryButton 실패 표시', () => {
  it('오류 봉투를 code 와 message 로 풀어 보여 준다 — [object Object] 가 아니다', async () => {
    if (!IS_DEV_ENTRY) return; // 플래그가 꺼진 환경에서는 컴포넌트가 없다
    vi.mocked(enterAsDeveloper).mockRejectedValue({
      code: 'INVALID_MEMBER_TOKEN',
      message: '유효하지 않거나 만료된 로그인 세션입니다.',
      errors: [],
      warnings: [],
    });

    render(
      <MemoryRouter>
        <DevEntryButton />
      </MemoryRouter>,
    );
    screen.getByLabelText('개발자로 입장').click();

    const alert = await waitFor(() => screen.getByRole('alert'));
    expect(alert.textContent).toContain('INVALID_MEMBER_TOKEN');
    expect(alert.textContent).toContain('유효하지 않거나 만료된 로그인 세션입니다.');
    expect(alert.textContent).not.toContain('[object Object]');
  });
});
