// @vitest-environment jsdom
// 내 부스 관리창 전면 개편 (S15P21A604-817).
//
// 잠그는 것 — ① 스튜디오 진입이 없다 ② 운영 관리 4카드 + 반납이 맞는 패널을 연다 ③ 부스 이름
// 인라인 편집은 facade 확보 전 잠기고, 본문을 mutation 직전 캐시에서 읽는다 ④ 미리보기 폴백 사슬은
// slot → default → 문구 셋으로 끝나고 2.5D 삽화를 끼우지 않는다 ⑤ AI 에셋 섹션은 플래그 한 줄로 사라진다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { __resetSessionForTests, setMemberSession } from '../../../auth/model/session';
import { __resetGameClientUiForTests, getGameClientUiSnapshot } from '../../../world/model/gameClientUi';

const getMyBooth = vi.fn();
const getBooth = vi.fn();
const putFacade = vi.fn();
const getMyProjects = vi.fn();
const getAiAgent = vi.fn();
const notify = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => getMyBooth() },
}));
vi.mock('../../../../entities/booth/facadeApi.select', () => ({
  facadeApi: {
    getBooth: (...args: unknown[]) => getBooth(...args),
    putFacade: (...args: unknown[]) => putFacade(...args),
  },
}));
vi.mock('../../../../entities/project/api.select', () => ({
  projectApi: { getMyProjects: (...args: unknown[]) => getMyProjects(...args) },
}));
vi.mock('../../../../entities/aiAgent/api', async () => {
  const actual = await vi.importActual<typeof import('../../../../entities/aiAgent/api')>(
    '../../../../entities/aiAgent/api',
  );
  return { ...actual, getAiAgent: (...args: unknown[]) => getAiAgent(...args) };
});
vi.mock('../../../../unity/host/boothLayoutBridge', () => ({
  notifyCurrentBoothSlotChanged: (...args: unknown[]) => notify(...args),
}));

const future = () => new Date(Date.now() + 60_000).toISOString();
const facade = { themeCode: 'DEFAULT', primaryColor: '#3B82F6', signText: '간판', logoUrl: null };
const myBooth = {
  boothId: 42,
  name: '내 부스',
  lease: { slotId: 6, slotCode: 'F11-R06', endsAt: future() },
};

beforeEach(() => {
  getMyBooth.mockReset().mockResolvedValue(myBooth);
  getBooth.mockReset().mockResolvedValue({ boothId: 42, name: '내 부스', leaseStatus: 'ACTIVE', facade, homepageUrl: null });
  putFacade.mockReset().mockResolvedValue(facade);
  getMyProjects.mockReset().mockResolvedValue({ projects: [] });
  getAiAgent.mockReset().mockResolvedValue({ agentId: 1, boothId: 42 });
  notify.mockReset();
  __resetGameClientUiForTests();
  setMemberSession('member-token', future());
});
afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

async function renderOverlay() {
  const { BoothManagementOverlay } = await import('../../ui/BoothManagementOverlay');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <BoothManagementOverlay onClose={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, client };
}

async function nameInput() {
  const input = (await screen.findByLabelText('부스 이름')) as HTMLInputElement;
  await waitFor(() => expect(input.disabled).toBe(false));
  return input;
}

describe('내 부스 관리창 개편 (-817)', () => {
  it('스튜디오로 가는 길이 없다', async () => {
    await renderOverlay();
    await screen.findByText('부스 운영 관리');
    expect(screen.queryByText(/부스 스튜디오/)).toBeNull();
    expect(document.querySelector('.bm-mini-preview')).toBeNull();
  });

  it('운영 관리 4카드가 각자 맞는 패널을 연다', async () => {
    await renderOverlay();
    for (const [label, kind] of [
      ['프로젝트', 'project'],
      ['설문', 'survey'],
      ['상담', 'consultation'],
      ['AI 직원', 'ai-agent'],
    ] as const) {
      fireEvent.click(await screen.findByRole('button', { name: new RegExp('^' + label) }));
      await waitFor(() => expect(getGameClientUiSnapshot().managementPanel).toEqual({ kind, boothId: 42 }));
    }
  });

  it('프로젝트가 없으면 미등록, 있으면 이름·썸네일', async () => {
    getMyProjects.mockResolvedValue({ projects: [{ projectId: 1, name: '데모 프로젝트', thumbnailUrl: 'https://x/t.png' }] });
    await renderOverlay();
    expect(await screen.findByText('데모 프로젝트')).toBeTruthy();
    expect((document.querySelector('.bm-thumb') as HTMLImageElement).src).toBe('https://x/t.png');
  });

  it('프로젝트 0건이면 프로젝트 미등록', async () => {
    await renderOverlay();
    expect(await screen.findByText('프로젝트 미등록')).toBeTruthy();
  });

  describe('부스 이름 편집', () => {
    it('facade 확보 전에는 잠긴다', async () => {
      getBooth.mockReturnValue(new Promise(() => {}));
      await renderOverlay();
      const input = (await screen.findByLabelText('부스 이름')) as HTMLInputElement;
      expect(input.disabled).toBe(true);
      expect(input.value).toBe('내 부스');
    });

    it('안 바뀌었거나 공백만이면 보내지 않는다', async () => {
      await renderOverlay();
      const input = await nameInput();
      fireEvent.submit(input.form!);
      fireEvent.change(input, { target: { value: '   ' } });
      fireEvent.submit(input.form!);
      expect(putFacade).not.toHaveBeenCalled();
    });

    it('trim 한 이름을 mutation 직전 캐시의 facade 4필드와 함께 보내고 슬롯을 한 번 알린다', async () => {
      const { client } = await renderOverlay();
      const input = await nameInput();
      // 렌더 뒤 캐시를 바꿔 둔다 — 클로저의 옛 facade 가 아니라 이 값이 나가야 한다
      const fresh = { ...facade, signText: '새 간판' };
      client.setQueryData(['booth-detail', 42], { boothId: 42, name: '내 부스', leaseStatus: 'ACTIVE', facade: fresh, homepageUrl: null });

      fireEvent.change(input, { target: { value: '  새 이름  ' } });
      fireEvent.submit(input.form!);

      await waitFor(() => expect(putFacade).toHaveBeenCalledWith(42, { ...fresh, name: '새 이름' }));
      await waitFor(() => expect(notify).toHaveBeenCalledTimes(1));
    });

    it('field=name 오류가 입력 아래 alert 로 선다', async () => {
      putFacade.mockRejectedValue({
        code: 'VALIDATION_FAILED',
        message: '검증 실패',
        errors: [{ field: 'name', message: '이름은 100자 이하' }],
        warnings: [],
      });
      await renderOverlay();
      const input = await nameInput();
      fireEvent.change(input, { target: { value: '긴 이름' } });
      fireEvent.submit(input.form!);
      expect((await screen.findByRole('alert')).textContent).toBe('이름은 100자 이하');
      expect(notify).not.toHaveBeenCalled();
    });
  });

  describe('미리보기 폴백', () => {
    it('slot → default → 문구, 셋으로 끝난다', async () => {
      await renderOverlay();
      const img = () => document.querySelector('.bm-preview-img') as HTMLImageElement | null;
      await waitFor(() => expect(img()?.getAttribute('src')).toBe('/booth-preview/F11-R06.png'));
      fireEvent.error(img()!);
      expect(img()?.getAttribute('src')).toBe('/booth-preview/default.webp');
      fireEvent.error(img()!);
      expect(img()).toBeNull();
      expect(screen.getByText('부스 미리보기를 불러올 수 없습니다.')).toBeTruthy();
      expect(document.querySelector('.bm-mini-preview')).toBeNull();
    });

    it('slotCode 가 없으면 default 부터', async () => {
      getMyBooth.mockResolvedValue({ ...myBooth, lease: { ...myBooth.lease, slotCode: null } });
      await renderOverlay();
      await waitFor(() =>
        expect(document.querySelector('.bm-preview-img')?.getAttribute('src')).toBe('/booth-preview/default.webp'),
      );
    });
  });

  describe('AI 전시 에셋 섹션', () => {
    it('기본은 잠금 자리표 — 케이스 4 + 버튼 2 전부 disabled', async () => {
      await renderOverlay();
      await screen.findByText('AI 전시 에셋 관리');
      expect(document.querySelectorAll('.bm-ai-case')).toHaveLength(4);
      for (const name of ['AI 에셋 생성', '케이스에 배치']) {
        const btn = screen.getByRole('button', { name }) as HTMLButtonElement;
        expect(btn.disabled).toBe(true);
        expect(btn.getAttribute('aria-disabled')).toBe('true');
      }
    });
    // 플래그 false 렌더는 boothManagementAiFlagOff.test.tsx — vi.mock 은 파일 단위라 따로 둔다
  });
});
