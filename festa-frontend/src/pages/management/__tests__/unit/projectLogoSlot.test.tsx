// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../../../entities/project/api.select', async () => {
  const mockApi = await import('../../../../entities/project/api.mock');
  return { projectApi: mockApi };
});

import {
  __resetProjectMockForTests,
  __setOwnedProjectForTests,
} from '../../../../entities/project/api.mock';
import { __resetProjectEditForTests, getProjectEditSnapshot } from '../../../../features/project/model/edit';
import * as logoUpload from '../../../../features/project/model/logoUpload';
import { ProjectManagementPage } from '../../ProjectManagementPage';

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/management/1/project']}>
        <Routes>
          <Route path="/management/:boothId/project" element={<ProjectManagementPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  __resetProjectEditForTests();
  __resetProjectMockForTests();
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:project-logo') });
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('project logo upload slot', () => {
  it('기존 thumbnailUrl을 현재 로고로 표시한다', async () => {
    __setOwnedProjectForTests({ thumbnailUrl: 'https://cdn.example/logo.webp' });
    renderPage();

    const image = await screen.findByRole('img', { name: '현재 프로젝트 로고' });
    expect(image.getAttribute('src')).toBe('https://cdn.example/logo.webp');
  });

  it('업로드 중 저장을 막고 완료 경로만 dirty로 저장한다', async () => {
    let complete!: (result: { thumbnailUrl: string }) => void;
    vi.spyOn(logoUpload, 'uploadProjectLogo').mockImplementation(() => new Promise((resolve) => { complete = resolve; }));
    vi.spyOn(logoUpload, 'fetchProjectLogo').mockResolvedValue(new Blob(['image']));
    renderPage();
    const input = await screen.findByLabelText('프로젝트 로고 이미지 선택');
    const file = new File(['logo'], 'team-logo.png', { type: 'image/png' });

    fireEvent.change(input, { target: { files: [file] } });

    expect(screen.getByRole('img', { name: '선택한 프로젝트 로고 미리보기' }).getAttribute('src'))
      .toBe('blob:project-logo');
    expect(screen.getByText('이미지를 업로드하고 확인하는 중입니다.')).toBeTruthy();
    expect((screen.getByRole('button', { name: '변경 저장' }) as HTMLButtonElement).disabled).toBe(true);
    expect(getProjectEditSnapshot().dirty.size).toBe(0);
    await act(async () => complete({ thumbnailUrl: '/api/v1/booths/1/project-logos/logo-1/content' }));
    await waitFor(() => expect(getProjectEditSnapshot().dirty.has('thumbnailUrl')).toBe(true));
    expect((screen.getByRole('button', { name: '변경 저장' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '변경 저장' }));
    await screen.findByText('저장했습니다');
    expect(getProjectEditSnapshot().draft.thumbnailUrl).toBe('/api/v1/booths/1/project-logos/logo-1/content');
  });

  it('업로드 실패는 기존 로고를 지키고 재선택할 수 있다', async () => {
    vi.spyOn(logoUpload, 'uploadProjectLogo').mockRejectedValue(new Error('이미지 검증 실패'));
    __setOwnedProjectForTests({ thumbnailUrl: 'https://cdn.example/old.webp' });
    renderPage();
    const input = await screen.findByLabelText('프로젝트 로고 이미지 선택');
    fireEvent.change(input, { target: { files: [new File(['logo'], 'logo.png', { type: 'image/png' })] } });
    await screen.findByText('이미지 검증 실패');
    expect(getProjectEditSnapshot().draft.thumbnailUrl).toBe('https://cdn.example/old.webp');
    expect(getProjectEditSnapshot().dirty.size).toBe(0);
    expect((input as HTMLInputElement).disabled).toBe(false);
  });
});
