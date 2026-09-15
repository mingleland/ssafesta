// @vitest-environment jsdom
// 부스 이름 편집 + facade 저장 후 월드 반영 회귀 (S15P21A604-786, GitLab #171).
//
// 잠그는 것 둘.
//   ① 응답이 facade 4필드뿐이라는 **비대칭**이 이름을 지우지 않는다
//   ② facade 저장 성공이 Unity 슬롯 알림을 부른다 — 이름만이 아니라 signText·대표색도 마찬가지다
//
// ②가 필요한 이유: 지금까지 알림은 게시(publish)에만 걸려 있었고 `boothLayoutBridge` 주석이
// "facade 저장은 보내도 아무 일이 없다" 고 적고 있었다. 그건 -659 이후 틀린 서술이다 —
// RequestReload 는 레이아웃 서명을 비교하기 전에 간판·외벽을 무조건 다시 읽는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ApiError } from '../../../../shared/api/client';
import type { BoothDetail, BoothFacade, FacadePutRequest } from '../../../../entities/booth/types';

const getBooth = vi.fn();
const putFacade = vi.fn();
const notifyCurrentBoothSlotChanged = vi.fn();

vi.mock('../../../../entities/booth/facadeApi.select', () => ({
  facadeApi: {
    getBooth: (boothId: number) => getBooth(boothId),
    putFacade: (boothId: number, body: unknown) => putFacade(boothId, body),
  },
}));

vi.mock('../../../../unity/host/boothLayoutBridge', () => ({
  notifyCurrentBoothSlotChanged: (qc: unknown) => notifyCurrentBoothSlotChanged(qc),
}));

const { FacadePanel } = await import('../../ui/FacadePanel');

const BOOTH_ID = 7;
const FACADE: BoothFacade = { themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null };

function booth(name: string): BoothDetail {
  return { boothId: BOOTH_ID, name, leaseStatus: 'ACTIVE', facade: FACADE, homepageUrl: null };
}

function fieldError(field: string, message: string): ApiError {
  return {
    code: 'VALIDATION_FAILED',
    message: '요청 값이 올바르지 않습니다.',
    requestId: 'mock_test',
    errors: [{ rule: 'FIELD_INVALID', field, message }],
    warnings: [],
  };
}

beforeEach(() => {
  getBooth.mockReset();
  putFacade.mockReset();
  notifyCurrentBoothSlotChanged.mockReset();
  getBooth.mockResolvedValue(booth('내 부스'));
  // 서버는 facade 4필드만 돌려준다 — name 이 없다. 이 비대칭이 이 파일의 주제다.
  putFacade.mockResolvedValue(FACADE);
});
afterEach(cleanup);

async function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <FacadePanel boothId={BOOTH_ID} />
    </QueryClientProvider>,
  );
  return await screen.findByDisplayValue('내 부스');
}

const save = () => fireEvent.click(screen.getByRole('button', { name: /저장/ }));
const sentBody = (): FacadePutRequest => putFacade.mock.calls[0][1] as FacadePutRequest;

describe('이름 편집 — 4필드 응답이 이름을 지우지 않는다', () => {
  it('저장 성공 뒤에도 입력칸에 이름이 남아 있다', async () => {
    const input = await renderPanel();
    fireEvent.change(input, { target: { value: '우리 부스' } });
    save();
    await waitFor(() => expect(putFacade).toHaveBeenCalledTimes(1));
    expect(sentBody().name).toBe('우리 부스');
    // 응답에 name 이 없어도 화면 값이 유지돼야 한다
    expect(screen.getByDisplayValue('우리 부스')).toBeTruthy();
  });

  it('앞뒤 공백은 FE 가 지워서 보낸다 — BE 1자 검증은 공백 한 칸을 통과시킨다', async () => {
    const input = await renderPanel();
    fireEvent.change(input, { target: { value: '  우리 부스  ' } });
    save();
    await waitFor(() => expect(putFacade).toHaveBeenCalledTimes(1));
    expect(sentBody().name).toBe('우리 부스');
  });

  it('공백만 입력하면 name 키를 아예 보내지 않는다 — 생략은 현재 이름 유지다', async () => {
    const input = await renderPanel();
    fireEvent.change(input, { target: { value: '   ' } });
    save();
    await waitFor(() => expect(putFacade).toHaveBeenCalledTimes(1));
    expect('name' in sentBody()).toBe(false);
  });

  it('이름을 안 고치면 name 키를 보내지 않는다 — 불필요한 쓰기를 만들지 않는다', async () => {
    await renderPanel();
    save();
    await waitFor(() => expect(putFacade).toHaveBeenCalledTimes(1));
    expect('name' in sentBody()).toBe(false);
  });

  it('name field 오류가 이름 입력칸 아래에 뜬다', async () => {
    putFacade.mockRejectedValue(fieldError('name', '부스 이름은 1자 이상 100자 이하여야 합니다.'));
    const input = await renderPanel();
    fireEvent.change(input, { target: { value: '바꾼 이름' } });
    save();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('부스 이름은 1자 이상');
  });
});

describe('facade 저장 성공은 월드에 알린다', () => {
  it('이름을 바꿨을 때 슬롯 알림이 한 번 나간다', async () => {
    const input = await renderPanel();
    fireEvent.change(input, { target: { value: '우리 부스' } });
    save();
    await waitFor(() => expect(notifyCurrentBoothSlotChanged).toHaveBeenCalledTimes(1));
  });

  it('이름을 안 바꾸고 facade 만 저장해도 알림이 나간다 — 이름만 특별 취급하지 않는다', async () => {
    await renderPanel();
    save();
    await waitFor(() => expect(notifyCurrentBoothSlotChanged).toHaveBeenCalledTimes(1));
    expect('name' in sentBody()).toBe(false);
  });

  it('저장이 실패하면 알리지 않는다 — 안 바뀐 것을 다시 읽게 하지 않는다', async () => {
    putFacade.mockRejectedValue(fieldError('signText', '간판 문구는 60자 이하여야 합니다.'));
    await renderPanel();
    save();
    await waitFor(() => expect(putFacade).toHaveBeenCalledTimes(1));
    expect(notifyCurrentBoothSlotChanged).not.toHaveBeenCalled();
  });

  it('알림이 false 를 돌려줘도(월드 미진입) 저장은 성공으로 끝난다', async () => {
    notifyCurrentBoothSlotChanged.mockReturnValue(false);
    const input = await renderPanel();
    fireEvent.change(input, { target: { value: '우리 부스' } });
    save();
    await waitFor(() => expect(screen.getByDisplayValue('우리 부스')).toBeTruthy());
  });
});

