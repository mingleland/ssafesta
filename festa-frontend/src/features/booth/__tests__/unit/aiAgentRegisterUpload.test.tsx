// @vitest-environment jsdom
// AI 직원 등록+문서 업로드 통합 (S15P21A604-724) — QA 발견: 예전에는 이름/프롬프트 저장 성공
// 후에만 업로드가 풀려 등록과 업로드가 두 단계였다. 등록 폼에서 미리 문서를 선택해두면
// 저장 성공 직후 같은 agentId 로 순차 업로드까지 한 번에 끝나야 한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const getAiAgent = vi.fn();
const createAiAgent = vi.fn();
const updateAiAgent = vi.fn();
const listAiDocuments = vi.fn();
const uploadAiDocument = vi.fn();
const replaceAiDocument = vi.fn();

vi.mock('../../../../entities/aiAgent/api', async () => {
  const actual = await vi.importActual<typeof import('../../../../entities/aiAgent/api')>(
    '../../../../entities/aiAgent/api',
  );
  return {
    ...actual,
    getAiAgent: (...args: unknown[]) => getAiAgent(...args),
    createAiAgent: (...args: unknown[]) => createAiAgent(...args),
    updateAiAgent: (...args: unknown[]) => updateAiAgent(...args),
    listAiDocuments: (...args: unknown[]) => listAiDocuments(...args),
    uploadAiDocument: (...args: unknown[]) => uploadAiDocument(...args),
    replaceAiDocument: (...args: unknown[]) => replaceAiDocument(...args),
  };
});

const createdAgent = {
  agentId: 1,
  boothId: 42,
  name: 'FESTA 안내 직원',
  role: 'PROJECT_DOCENT' as const,
  tone: 'FRIENDLY' as const,
  systemPrompt: '친절하게 답한다',
  responseLength: 'MEDIUM' as const,
  servicePrice: 0,
  handoffEnabled: false,
  forbiddenTopics: [],
};

beforeEach(() => {
  getAiAgent.mockReset();
  createAiAgent.mockReset();
  updateAiAgent.mockReset();
  listAiDocuments.mockReset();
  uploadAiDocument.mockReset();
  replaceAiDocument.mockReset();

  getAiAgent.mockResolvedValueOnce(null).mockResolvedValue(createdAgent);
  createAiAgent.mockResolvedValue(createdAgent);
  listAiDocuments.mockResolvedValue({ documents: [], quota: { countLimit: 5, bytesLimit: 20_000_000 } });
  uploadAiDocument.mockResolvedValue({ duplicate: false, documentId: 10, processingStatus: 'QUEUED' });
});

afterEach(() => cleanup());

async function renderTab() {
  const { AiAgentManagementTab } = await import('../../ui/AiAgentManagementTab');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AiAgentManagementTab boothId={42} />
    </QueryClientProvider>,
  );
}

describe('AI 직원 등록+문서 업로드 통합 (-724)', () => {
  it('미등록 상태에서 파일을 미리 선택해두면 목록에 뜬다', async () => {
    await renderTab();
    await screen.findByText('미등록');

    const file = new File(['contents'], 'guide.pdf', { type: 'application/pdf' });
    const input = screen.getByText('파일 선택').parentElement!.querySelector('input[type="file"]')!;
    Object.defineProperty(input, 'files', { configurable: true, value: [file] });
    fireEvent.change(input);

    expect(await screen.findByText('guide.pdf')).toBeTruthy();
  });

  it('등록 저장 성공 직후 선택해둔 문서를 같은 agentId로 업로드한다', async () => {
    await renderTab();
    await screen.findByText('미등록');

    fireEvent.change(screen.getByPlaceholderText('예: FESTA 안내 직원'), { target: { value: 'FESTA 안내 직원' } });
    fireEvent.change(screen.getByPlaceholderText('AI 직원이 지켜야 할 답변 원칙을 작성하세요.'), {
      target: { value: '친절하게 답한다' },
    });

    const file = new File(['contents'], 'guide.pdf', { type: 'application/pdf' });
    const input = screen.getByText('파일 선택').parentElement!.querySelector('input[type="file"]')!;
    Object.defineProperty(input, 'files', { configurable: true, value: [file] });
    fireEvent.change(input);
    await screen.findByText('guide.pdf');

    fireEvent.click(screen.getByRole('button', { name: 'AI 직원 등록' }));

    await waitFor(() => expect(createAiAgent).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(uploadAiDocument).toHaveBeenCalledWith(createdAgent.agentId, file));
    expect(await screen.findByText('문서 1건 업로드 완료')).toBeTruthy();
  });
});
