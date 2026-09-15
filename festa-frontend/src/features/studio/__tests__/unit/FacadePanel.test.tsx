// @vitest-environment jsdom
// -86 완료 조건 "field 오류가 해당 입력 옆에 표기"의 회귀 방어.
// 이 조건은 봉투 message 하나를 폼 하단에 내는 것으로는 충족되지 않는다 — 4필드 중 어느 것을
// 고쳐야 하는지가 화면에 나와야 한다. 그래서 "메시지가 떴다"가 아니라 "그 입력 옆에 떴다"를 묻는다.
// 실서버로는 이 경로를 밟기 어렵다: 테마는 select, 대표색은 라디오 12개, 간판은 maxLength=60 이라
// UI 만으로는 서버 400 을 만들 수 없다. mock 을 세워 봉투를 직접 주는 이유다.
// 출처: docs/08 §1.3-1(FIELD_INVALID·field 키), specs/005 contracts/layout-api.md §6
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ApiError } from '../../../../shared/api/client';
import type { BoothDetail } from '../../../../entities/booth/types';

const getBooth = vi.fn();
const putFacade = vi.fn();

vi.mock('../../../../entities/booth/facadeApi.select', () => ({
  facadeApi: {
    getBooth: (boothId: number) => getBooth(boothId),
    putFacade: (boothId: number, body: unknown) => putFacade(boothId, body),
  },
}));

const { FacadePanel } = await import('../../ui/FacadePanel');

const BOOTH_ID = 7;

// facadeApi.mock.ts 의 fieldError 와 같은 봉투 — 실서버 Bean Validation 형태다.
function fieldError(field: string, message: string): ApiError {
  return {
    code: 'VALIDATION_FAILED',
    message: '요청 값이 올바르지 않습니다.',
    requestId: 'mock_test',
    errors: [{ rule: 'FIELD_INVALID', field, message }],
    warnings: [],
  };
}

function booth(facade: BoothDetail['facade']): BoothDetail {
  return { boothId: BOOTH_ID, name: '테스트 부스', leaseStatus: 'ACTIVE', facade, homepageUrl: null };
}

function renderPanel() {
  // retry:false — 저장 실패 분기를 보려면 재시도 대기가 없어야 한다
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <FacadePanel boothId={BOOTH_ID} />
    </QueryClientProvider>,
  );
}

// 저장 버튼까지 도달시킨다 — 하이드레이션이 끝나야 폼이 서버 값을 갖는다.
async function renderAndSave() {
  renderPanel();
  const save = await screen.findByRole('button', { name: '저장' });
  fireEvent.click(save);
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('FacadePanel — field 단위 오류 표기', () => {
  it('primaryColor 위반은 대표색 fieldset 옆에 뜬다', async () => {
    getBooth.mockResolvedValue(booth({ themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null }));
    putFacade.mockRejectedValue(fieldError('primaryColor', '대표색은 팔레트 12색 중 하나여야 합니다.'));

    await renderAndSave();

    const message = await screen.findByText('대표색은 팔레트 12색 중 하나여야 합니다.');
    // 폼 하단이 아니라 대표색 fieldset 바로 뒤여야 한다 — 위치를 형제 관계로 고정한다.
    const fieldset = screen.getByRole('group', { name: '대표색' });
    expect(message.previousElementSibling).toBe(fieldset);
  });

  it('signText 위반은 간판 문구 옆에만 뜨고 대표색 옆에는 안 뜬다', async () => {
    getBooth.mockResolvedValue(booth({ themeCode: 'DEFAULT', primaryColor: null, signText: '초과', logoUrl: null }));
    putFacade.mockRejectedValue(fieldError('signText', '간판 문구는 60자 이하여야 합니다.'));

    await renderAndSave();

    const message = await screen.findByText('간판 문구는 60자 이하여야 합니다.');
    expect(message.previousElementSibling?.textContent).toContain('간판 문구');
    // 다른 필드로 새지 않는다 — errors[]의 field 하나만 반영돼야 한다.
    expect(screen.queryByText('대표색은 팔레트 12색 중 하나여야 합니다.')).toBeNull();
  });

  it('field 로 표시된 오류는 폼 하단 봉투 message 로 중복되지 않는다', async () => {
    getBooth.mockResolvedValue(booth({ themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null }));
    putFacade.mockRejectedValue(fieldError('logoUrl', '로고 URL 형식이 올바르지 않습니다.'));

    await renderAndSave();

    await screen.findByText('로고 URL 형식이 올바르지 않습니다.');
    expect(screen.queryByText('요청 값이 올바르지 않습니다.')).toBeNull();
  });

  it('field 가 없는 오류는 봉투 message 를 그대로 낸다 — 삼키지 않는다', async () => {
    getBooth.mockResolvedValue(booth({ themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null }));
    putFacade.mockRejectedValue({
      code: 'INTERNAL_ERROR',
      message: '일시적인 오류가 발생했습니다.',
      requestId: 'mock_test',
      errors: [],
      warnings: [],
    } satisfies ApiError);

    await renderAndSave();

    await waitFor(() => {
      expect(screen.queryByText('일시적인 오류가 발생했습니다.')).not.toBeNull();
    });
  });
});
