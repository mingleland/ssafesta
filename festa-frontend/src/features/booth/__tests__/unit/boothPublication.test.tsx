// @vitest-environment jsdom
// 관리창 게시 흐름 (S15P21A604-898). 완료 조건 7시나리오를 그대로 잠근다.
//
// ① 프로젝트 없음 → "준비 중" + 게시 버튼은 게이트를 연다(게시 호출 0)
// ② 프로젝트 있음·미게시 → "준비 중" + 게시 가능
// ③ 최초 게시 → getDraft → putDraft(expectedRevision 0) → publish → notify, 상태가 "운영 중" 으로
// ④ 운영 중 + presentation 동일 → 버튼 없음 (프로젝트 텍스트 수정은 presentation 이 아니다)
// ⑤ 운영 중 + presentation 변경(legacy 게시본) → "변경사항 적용" → 새 회차
// ⑥ publish 실패 → 상태 그대로·alert, 캐시 미변경
// ⑦ 409 → 안내 뒤 다시 누르면 최신 revision 으로 성공
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { __resetSessionForTests, setMemberSession } from '../../../auth/model/session';
import { __resetGameClientUiForTests, getGameClientUiSnapshot } from '../../../world/model/gameClientUi';

const getMyBooth = vi.fn();
const getBooth = vi.fn();
const getMyProjects = vi.fn();
const getAiAgent = vi.fn();
const notify = vi.fn();
const getDraft = vi.fn();
const putDraft = vi.fn();
const publishLayout = vi.fn();
const getPublishedLayout = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({ leaseApi: { getMyBooth: () => getMyBooth() } }));
vi.mock('../../../../entities/booth/facadeApi.select', () => ({
  facadeApi: { getBooth: (...a: unknown[]) => getBooth(...a), putFacade: vi.fn() },
}));
vi.mock('../../../../entities/project/api.select', () => ({
  projectApi: { getMyProjects: (...a: unknown[]) => getMyProjects(...a) },
}));
vi.mock('../../../../entities/aiAgent/api', async () => {
  const actual = await vi.importActual<typeof import('../../../../entities/aiAgent/api')>('../../../../entities/aiAgent/api');
  return { ...actual, getAiAgent: (...a: unknown[]) => getAiAgent(...a) };
});
vi.mock('../../../../entities/booth/layoutApi.select', () => ({
  layoutApi: {
    getDraft: (...a: unknown[]) => getDraft(...a),
    putDraft: (...a: unknown[]) => putDraft(...a),
    publishLayout: (...a: unknown[]) => publishLayout(...a),
    getPublishedLayout: (...a: unknown[]) => getPublishedLayout(...a),
  },
}));
vi.mock('../../../../unity/host/boothLayoutBridge', () => ({
  notifyBoothSlotChanged: (...a: unknown[]) => notify(...a),
}));

const future = () => new Date(Date.now() + 60_000).toISOString();
const facade = { themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null };
const myBooth = { boothId: 42, name: '내 부스', lease: { slotId: 6, slotCode: 'F11-R06', endsAt: future() } };
const project = { projectId: 1, name: '데모 프로젝트', thumbnailUrl: null };
const emptyLayout = { schemaVersion: 1, template: 'PROJECT_EXHIBITION', objects: [] as unknown[] };

// 서버 상태를 흉내 내는 작은 저장소 — invalidate 뒤 재조회가 게시 결과를 돌려줘야 화면 전이가 성립한다
type Layout = typeof emptyLayout;
const server = { version: null as number | null, draft: null as (Layout & { revision: number }) | null, published: null as Layout | null };
function detail() {
  return { boothId: 42, name: '내 부스', leaseStatus: 'ACTIVE', facade, homepageUrl: null, publishedLayoutVersion: server.version };
}
function apiError(code: string, status: number) {
  return { code, message: code, status, errors: [], warnings: [] };
}

beforeEach(() => {
  server.version = null;
  server.draft = null;
  server.published = null;
  getMyBooth.mockReset().mockResolvedValue(myBooth);
  getBooth.mockReset().mockImplementation(async () => detail());
  getMyProjects.mockReset().mockResolvedValue({ projects: [project] });
  getAiAgent.mockReset().mockResolvedValue(null);
  notify.mockReset();
  getDraft.mockReset().mockImplementation(async () => server.draft);
  putDraft.mockReset().mockImplementation(async (_id: number, body: { expectedRevision: number } & Layout) => {
    const { expectedRevision, ...doc } = body;
    server.draft = { ...doc, revision: expectedRevision + 1 };
    return { boothId: 42, ...server.draft };
  });
  publishLayout.mockReset().mockImplementation(async () => {
    server.version = (server.version ?? 0) + 1;
    server.published = { ...server.draft! };
    return { boothId: 42, publishedVersion: server.version, publishedAt: future() };
  });
  getPublishedLayout.mockReset().mockImplementation(async () => {
    if (!server.published) throw apiError('LAYOUT_NOT_PUBLISHED', 404);
    return { boothId: 42, version: server.version, ...server.published };
  });
  __resetGameClientUiForTests();
  setMemberSession('member-token', future());
});
afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

async function renderOverlay() {
  const { BoothManagementOverlay } = await import('../../ui/BoothManagementOverlay');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <BoothManagementOverlay onClose={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByText('부스 운영 관리');
}

const status = () => document.querySelector('.bm-status')?.textContent?.trim();
const actionButton = () => document.querySelector('.bm-identity-action button') as HTMLButtonElement | null;

describe('관리창 게시 흐름 (-898)', () => {
  it('① 프로젝트 없음 — 준비 중, 게시 버튼은 등록 게이트를 연다', async () => {
    getMyProjects.mockResolvedValue({ projects: [] });
    await renderOverlay();
    await waitFor(() => expect(status()).toBe('준비 중'));
    expect(screen.getByText('프로젝트를 등록하면 게시할 수 있습니다')).toBeTruthy();
    await waitFor(() => expect(actionButton()!.disabled).toBe(false));
    fireEvent.click(actionButton()!);
    expect(await screen.findByText('프로젝트 등록이 필요합니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '프로젝트 등록' }));
    await waitFor(() => expect(getGameClientUiSnapshot().managementPanel).toEqual({ kind: 'project', boothId: 42 }));
    expect(putDraft).not.toHaveBeenCalled();
    expect(publishLayout).not.toHaveBeenCalled();
  });

  it('② 프로젝트 있음·미게시 — 준비 중 + 게시 가능', async () => {
    await renderOverlay();
    await waitFor(() => expect(status()).toBe('준비 중'));
    expect(actionButton()!.textContent).toBe('게시');
    expect(screen.getByText('게시하면 방문객에게 공개됩니다')).toBeTruthy();
  });

  it('③ 최초 게시 — draft 0 → publish → Unity 알림 → 운영 중', async () => {
    await renderOverlay();
    await waitFor(() => expect(actionButton()!.disabled).toBe(false));
    fireEvent.click(actionButton()!);
    await waitFor(() => expect(status()).toBe('운영 중'));
    expect(getDraft).toHaveBeenCalledWith(42);
    expect(putDraft).toHaveBeenCalledWith(42, { expectedRevision: 0, ...emptyLayout });
    expect(publishLayout).toHaveBeenCalledWith(42);
    expect(notify).toHaveBeenCalledWith(6);
    // 게시본 = 현재 presentation 이라 더 누를 것이 없다
    await waitFor(() => expect(actionButton()).toBeNull());
  });

  it('④ 운영 중 + 게시본 동일 — 프로젝트 이름이 바뀌어도 버튼이 없다', async () => {
    server.version = 3;
    server.published = { ...emptyLayout };
    getMyProjects.mockResolvedValue({ projects: [{ ...project, name: '이름 바꿈' }] });
    await renderOverlay();
    await waitFor(() => expect(status()).toBe('운영 중'));
    await waitFor(() => expect(getPublishedLayout).toHaveBeenCalled());
    expect(actionButton()).toBeNull();
  });

  it('⑤ 운영 중 + presentation 다름(Studio 가구) — 변경사항 적용 → 새 회차', async () => {
    const legacy = { ...emptyLayout, objects: [{ objectId: 'w', type: 'WALL_PLAIN', position: { x: 0, y: 0, z: 0 }, rotationY: 0 }] };
    server.version = 3;
    server.published = legacy;
    server.draft = { ...legacy, revision: 7 };
    await renderOverlay();
    await waitFor(() => expect(actionButton()?.textContent).toBe('변경사항 적용'));
    fireEvent.click(actionButton()!);
    await waitFor(() => expect(publishLayout).toHaveBeenCalled());
    expect(putDraft).toHaveBeenCalledWith(42, { expectedRevision: 7, ...emptyLayout });
    await waitFor(() => expect(actionButton()).toBeNull());
    expect(status()).toBe('운영 중');
    expect(server.version).toBe(4);
  });

  it('⑥ publish 실패 — 준비 중 그대로, alert', async () => {
    publishLayout.mockImplementation(async () => {
      throw apiError('LAYOUT_VALIDATION_FAILED', 409);
    });
    await renderOverlay();
    await waitFor(() => expect(actionButton()!.disabled).toBe(false));
    fireEvent.click(actionButton()!);
    expect((await screen.findByRole('alert')).textContent).toContain('게시하지 못했습니다');
    expect(status()).toBe('준비 중');
    expect(notify).not.toHaveBeenCalled();
    expect(server.version).toBeNull();
  });

  it('⑦ 409 revision conflict — 안내 뒤 재시도가 최신 revision 으로 성공', async () => {
    // 첫 PUT 이 나가기 전에 누군가 revision 2 를 저장한 상황 — 서버는 409, 다음 GET 이 2 를 준다
    putDraft.mockImplementationOnce(async () => {
      server.draft = { ...emptyLayout, revision: 2 };
      throw apiError('LAYOUT_REVISION_CONFLICT', 409);
    });
    await renderOverlay();
    await waitFor(() => expect(actionButton()!.disabled).toBe(false));
    fireEvent.click(actionButton()!);
    expect((await screen.findByRole('alert')).textContent).toContain('다시 눌러 게시');
    expect(publishLayout).not.toHaveBeenCalled();
    fireEvent.click(actionButton()!);
    await waitFor(() => expect(status()).toBe('운영 중'));
    expect(putDraft).toHaveBeenLastCalledWith(42, { expectedRevision: 2, ...emptyLayout });
    expect(publishLayout).toHaveBeenCalledTimes(1);
  });

  // 플래그 OFF(기본) 에서 과거 Studio 게시본의 AI_AGENT 보존 — 머지 전 필수 확인
  const aiObject = { objectId: 'ai-1', type: 'AI_AGENT', position: { x: -2.4, y: 0, z: -2.2 }, rotationY: 0, configId: 123 };

  it('⑧ 게시본 AI_AGENT(123) + 직원 123, 플래그 OFF → 운영 중, 버튼 없음', async () => {
    server.version = 5;
    server.published = { ...emptyLayout, objects: [aiObject] };
    getAiAgent.mockResolvedValue({ agentId: 123, boothId: 42 });
    await renderOverlay();
    await waitFor(() => expect(status()).toBe('운영 중'));
    await waitFor(() => expect(getPublishedLayout).toHaveBeenCalled());
    expect(actionButton()).toBeNull();
  });

  it('⑨ legacy 가구 + AI_AGENT(123) → 변경사항 적용해도 AI_AGENT 가 남는다', async () => {
    server.version = 5;
    server.published = { ...emptyLayout, objects: [aiObject, { objectId: 'w', type: 'WALL_PLAIN', position: { x: 0, y: 0, z: 0 }, rotationY: 0 }] };
    server.draft = { ...server.published, revision: 9 };
    getAiAgent.mockResolvedValue({ agentId: 123, boothId: 42 });
    await renderOverlay();
    await waitFor(() => expect(actionButton()?.textContent).toBe('변경사항 적용'));
    fireEvent.click(actionButton()!);
    await waitFor(() => expect(publishLayout).toHaveBeenCalled());
    const body = putDraft.mock.calls[0][1] as { expectedRevision: number; objects: Array<{ type: string; configId?: number }> };
    expect(body.expectedRevision).toBe(9);
    expect(body.objects).toHaveLength(1);
    expect(body.objects[0]).toMatchObject({ type: 'AI_AGENT', configId: 123 });
    await waitFor(() => expect(actionButton()).toBeNull());
  });

  it('⑩ 플래그 ON 전이 — 빈 게시본 + 직원 123 → 변경사항 적용 → AI_AGENT(123) 이 실린다', async () => {
    server.version = 2;
    server.published = { ...emptyLayout };
    server.draft = { ...emptyLayout, revision: 4 };
    getAiAgent.mockResolvedValue({ agentId: 123, boothId: 42 });
    await renderOverlay();
    await waitFor(() => expect(actionButton()?.textContent).toBe('변경사항 적용'));
    fireEvent.click(actionButton()!);
    await waitFor(() => expect(publishLayout).toHaveBeenCalled());
    const body = putDraft.mock.calls[0][1] as { expectedRevision: number; objects: Array<{ type: string; configId?: number }> };
    expect(body.expectedRevision).toBe(4);
    expect(body.objects).toEqual([expect.objectContaining({ type: 'AI_AGENT', configId: 123 })]);
    await waitFor(() => expect(actionButton()).toBeNull());
    expect(status()).toBe('운영 중');
  });
});
