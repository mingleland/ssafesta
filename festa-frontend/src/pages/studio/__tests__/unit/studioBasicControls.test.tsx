// @vitest-environment jsdom
// Booth Studio 기본 편집 조작 (S15P21A604-603) — 삭제 단축키와 전체 초기화.
//
// 여기서 고정하는 것은 두 가지다.
//   ① 캔버스에서 Delete·Backspace 가 선택 오브젝트를 지운다
//   ② **입력 필드 편집 중에는 가로채지 않는다** — 이게 없으면 좌표를 고치려고 Backspace 를
//      누른 순간 숫자가 아니라 오브젝트가 사라진다. 회귀하면 데이터가 조용히 날아가는 쪽이라
//      문구보다 이 경계를 먼저 지킨다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { LayoutObject } from '../../../../entities/layout/types';

const BOOTH_ID = 7;

const getMyBooth = vi.fn();
const getDraft = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => getMyBooth(), getSlots: () => Promise.resolve([]) },
}));

// jsdom 에는 ResizeObserver 가 없어 R3F Canvas 가 마운트에서 터진다. 이 테스트가 보는 것은
// 키 처리와 툴바라 렌더러 종류와 무관하므로 SVG 쪽으로 떨어뜨린다(제품 기본값은 R3F 그대로).
vi.mock('../../../../features/studio/ui/canvas/canvasRenderer', () => ({
  IS_R3F_CANVAS: false,
  IS_VISUAL_ACCEPTANCE: false,
  VISUAL_ACCEPTANCE_FRAME_MS: 100,
}));

vi.mock('../../../../entities/layout/api.select', () => ({
  layoutApi: {
    getDraft: (boothId: number) => getDraft(boothId),
    getTemplates: () => Promise.resolve({ templates: [] }),
  },
}));

const { StudioPage } = await import('../../StudioPage');
const { __resetSessionForTests, markBootstrapped, setMemberSession } = await import(
  '../../../../features/auth/model/session'
);

function object(objectId: string, x: number): LayoutObject {
  return { objectId, type: 'DECORATION', position: { x, y: 0, z: 0 }, rotationY: 0 };
}

function renderStudio() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/app/studio/${BOOTH_ID}`]}>
        <Routes>
          <Route path="/app/studio/:boothId" element={<StudioPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** 상태 바가 "n / max" 로 배치 수를 보여준다 — 개수 판정을 그 표시로 한다 */
async function countText(): Promise<string> {
  const cube = await screen.findByText(/\/\s*\d+$/);
  return cube.textContent ?? '';
}

// jsdom 의 SVGElement 에는 getScreenCTM 이 없어 toWorld() 가 TypeError 로 죽는다(브라우저에는 있다).
// null 을 주면 toWorld 가 설계대로 null 을 돌려주고 드래그는 시작되지 않는다 — 선택은 그 앞에서
// 이미 일어나므로 이 테스트가 보려는 것에는 영향이 없다.
beforeEach(() => {
  Object.defineProperty(SVGElement.prototype, 'getScreenCTM', {
    value: () => null,
    configurable: true,
    writable: true,
  });
});

beforeEach(() => {
  __resetSessionForTests();
  setMemberSession('token', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
  getMyBooth.mockReset();
  getDraft.mockReset();
  getMyBooth.mockResolvedValue({ boothId: BOOTH_ID, name: '내 부스', status: 'ACTIVE', lease: null });
  getDraft.mockResolvedValue({
    template: 'PROJECT_EXHIBITION',
    objects: [object('a', -1), object('b', 1)],
    revision: 3,
    publishedVersion: null,
  });
});

afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

describe('Booth Studio 기본 편집 조작', () => {
  it('선택된 오브젝트를 Delete 로 지운다', async () => {
    const { container } = renderStudio();
    await waitFor(async () => expect(await countText()).toContain('2'));

    // 캔버스에서 하나 고른다 — pointerDown 이 beginDrag 로 들어가 onSelect 를 먼저 부른다
    const target = container.querySelector('.iso-object');
    expect(target).not.toBeNull();
    fireEvent.pointerDown(target as Element);

    fireEvent.keyDown(document.body, { key: 'Delete' });

    await waitFor(async () => expect(await countText()).toMatch(/^\s*1\s*\//));
  });

  it('선택이 없으면 Delete 가 아무 것도 지우지 않는다', async () => {
    renderStudio();
    await waitFor(async () => expect(await countText()).toContain('2'));

    fireEvent.keyDown(document.body, { key: 'Delete' });
    expect(await countText()).toContain('2');
  });

  it('입력 필드 편집 중 Backspace 는 오브젝트를 지우지 않는다', async () => {
    renderStudio();
    await waitFor(async () => expect(await countText()).toContain('2'));

    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    fireEvent.keyDown(input, { key: 'Backspace' });

    expect(await countText()).toContain('2');
    input.remove();
  });

  it('배치가 있으면 초기화 버튼이 눌린다', async () => {
    renderStudio();
    await waitFor(async () => expect(await countText()).toContain('2'));

    const reset = screen.getByRole('button', { name: /초기화/ });
    expect(reset.hasAttribute('disabled')).toBe(false);
  });

  it('초기화를 누르면 곧바로 비우지 않고 확인을 먼저 묻는다', async () => {
    renderStudio();
    await waitFor(async () => expect(await countText()).toContain('2'));

    fireEvent.click(screen.getByRole('button', { name: /초기화/ }));

    // 되돌릴 수단이 없으므로 확인이 유일한 안전망이다 — 다이얼로그 없이 비우면 안 된다
    await screen.findByText('배치를 모두 비울까요?');
    expect(await countText()).toContain('2');
  });

  it('확인하면 배치가 비워진다', async () => {
    renderStudio();
    await waitFor(async () => expect(await countText()).toContain('2'));

    fireEvent.click(screen.getByRole('button', { name: /초기화/ }));
    await screen.findByText('배치를 모두 비울까요?');
    fireEvent.click(screen.getByRole('button', { name: '모두 비우기' }));

    await waitFor(async () => expect(await countText()).toMatch(/^\s*0\s*\//));
  });

  it('배치가 비어 있으면 초기화 버튼이 비활성이다', async () => {
    getDraft.mockResolvedValue({ template: 'PROJECT_EXHIBITION', objects: [], revision: 1, publishedVersion: null });
    renderStudio();
    await waitFor(async () => expect(await countText()).toMatch(/^\s*0\s*\//));

    expect(screen.getByRole('button', { name: /초기화/ }).hasAttribute('disabled')).toBe(true);
  });
});
