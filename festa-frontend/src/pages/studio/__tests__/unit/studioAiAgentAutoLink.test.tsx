// @vitest-environment jsdom
// Booth Studio — AI_AGENT 오브젝트 자동 configId 연결 (S15P21A604-732).
//
// QA 발견: AI 직원을 등록하고 문서도 올렸는데 월드에서 "아직 준비 중이에요"가 떴다. 원인은
// 부스 스튜디오가 AI_AGENT 오브젝트의 연결을 숫자 직접 입력으로만 받았고, 그 숫자(agentId)를
// 화면 어디에도 보여주지 않아 사용자가 입력할 방법이 없었던 것. 부스당 AI 직원은 한 명뿐이라
// (V15__agent_one_per_booth) 고를 필요가 없으므로 등록된 agentId로 항상 자동 연결한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { LayoutObject } from '../../../../entities/layout/types';

const BOOTH_ID = 7;

const getMyBooth = vi.fn();
const getDraft = vi.fn();
const getAiAgent = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getMyBooth: () => getMyBooth(), getSlots: () => Promise.resolve([]) },
}));

// jsdom 에는 ResizeObserver 가 없어 R3F Canvas 가 마운트에서 터진다 — 다른 StudioPage 테스트와
// 같은 이유로 SVG 렌더러로 떨어뜨린다.
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

vi.mock('../../../../entities/aiAgent/api', async () => {
  const actual = await vi.importActual<typeof import('../../../../entities/aiAgent/api')>(
    '../../../../entities/aiAgent/api',
  );
  return { ...actual, getAiAgent: (...args: unknown[]) => getAiAgent(...args) };
});

const { StudioPage } = await import('../../StudioPage');
const { __resetSessionForTests, markBootstrapped, setMemberSession } = await import(
  '../../../../features/auth/model/session'
);

const REGISTERED_AGENT = {
  agentId: 55,
  boothId: BOOTH_ID,
  name: 'FESTA 안내 직원',
  role: 'PROJECT_DOCENT' as const,
  tone: 'FRIENDLY' as const,
  systemPrompt: '친절하게 답한다',
  responseLength: 'MEDIUM' as const,
  servicePrice: 0,
  handoffEnabled: false,
  forbiddenTopics: [],
};

function aiAgentObject(configId: number | undefined): LayoutObject {
  return { objectId: 'npc-1', type: 'AI_AGENT', position: { x: 0, y: 0, z: 0 }, rotationY: 0, configId };
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

beforeEach(() => {
  Object.defineProperty(SVGElement.prototype, 'getScreenCTM', {
    value: () => null,
    configurable: true,
    writable: true,
  });
  __resetSessionForTests();
  setMemberSession('token', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
  getMyBooth.mockReset();
  getDraft.mockReset();
  getAiAgent.mockReset();
  getMyBooth.mockResolvedValue({ boothId: BOOTH_ID, name: '내 부스', status: 'ACTIVE', lease: null });
  getAiAgent.mockResolvedValue(REGISTERED_AGENT);
});

afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

function selectFirstObject(container: HTMLElement) {
  const target = container.querySelector('.iso-object');
  expect(target).not.toBeNull();
  fireEvent.pointerDown(target as Element);
}

describe('AI_AGENT 오브젝트 자동 연결 (-732)', () => {
  it('미연결(configId 없음) 오브젝트를 등록된 agentId로 자동 연결한다', async () => {
    getDraft.mockResolvedValue({
      template: 'PROJECT_EXHIBITION',
      objects: [aiAgentObject(undefined)],
      revision: 3,
      publishedVersion: null,
    });
    const { container } = renderStudio();

    await waitFor(() => expect(container.querySelector('.iso-object')).not.toBeNull());
    selectFirstObject(container);

    expect(await screen.findByText('연결된 AI 직원')).toBeTruthy();
    expect(await screen.findByText('FESTA 안내 직원')).toBeTruthy();
  });

  it('다른 agentId로 잘못 연결돼 있어도 등록된 agentId로 고쳐 연결한다', async () => {
    getDraft.mockResolvedValue({
      template: 'PROJECT_EXHIBITION',
      objects: [aiAgentObject(999)],
      revision: 3,
      publishedVersion: null,
    });
    const { container } = renderStudio();

    await waitFor(() => expect(container.querySelector('.iso-object')).not.toBeNull());
    selectFirstObject(container);

    expect(await screen.findByText('FESTA 안내 직원')).toBeTruthy();
  });

  it('등록된 AI 직원이 없으면 숫자 입력 대신 안내만 보여준다', async () => {
    getAiAgent.mockResolvedValue(null);
    getDraft.mockResolvedValue({
      template: 'PROJECT_EXHIBITION',
      objects: [aiAgentObject(undefined)],
      revision: 3,
      publishedVersion: null,
    });
    const { container } = renderStudio();

    await waitFor(() => expect(container.querySelector('.iso-object')).not.toBeNull());
    selectFirstObject(container);

    expect(await screen.findByText('등록된 AI 직원이 없습니다 — 내 부스 관리에서 AI 직원을 먼저 등록하세요.')).toBeTruthy();
    expect(screen.queryByLabelText('연결 콘텐츠 ID')).toBeNull();
  });
});
