// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../../../entities/project/api.select', async () => {
  const mockApi = await import('../../../../entities/project/api.mock');
  return { projectApi: mockApi };
});

const uploadMock = vi.fn();
vi.mock('../../../../features/project/model/logoUpload', async () => {
  const actual = await vi.importActual<typeof import('../../../../features/project/model/logoUpload')>(
    '../../../../features/project/model/logoUpload',
  );
  return { ...actual, uploadProjectLogo: (...args: unknown[]) => uploadMock(...args) };
});

import {
  __resetProjectMockForTests,
  __setOwnedProjectForTests,
} from '../../../../entities/project/api.mock';
import { __resetProjectEditForTests } from '../../../../features/project/model/edit';
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
  uploadMock.mockReset();
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

  it('선택하면 업로드하고 검증된 url 을 draft 에 넣어 저장을 활성화한다', async () => {
    uploadMock.mockResolvedValue({ thumbnailUrl: 'https://be.example/logos/abc/content' });
    renderPage();
    const input = await screen.findByLabelText('프로젝트 로고 이미지 선택');
    const file = new File(['logo'], 'team-logo.png', { type: 'image/png' });

    fireEvent.change(input, { target: { files: [file] } });

    expect(uploadMock).toHaveBeenCalledWith(file, 1);
    expect(await screen.findByText('업로드 완료 — 변경 저장을 눌러야 적용됩니다.')).toBeTruthy();
    expect(screen.getByRole('img', { name: '현재 프로젝트 로고' }).getAttribute('src'))
      .toBe('https://be.example/logos/abc/content');
    expect((screen.getByRole('button', { name: '변경 저장' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('업로드 실패 시 로컬 미리보기를 남기고 저장하므로 막는다', async () => {
    uploadMock.mockRejectedValue(new Error('이미지를 저장소에 올리지 못했습니다. 다시 시도해 주세요.'));
    renderPage();
    const input = await screen.findByLabelText('프로젝트 로고 이미지 선택');
    const file = new File(['logo'], 'team-logo.png', { type: 'image/png' });

    fireEvent.change(input, { target: { files: [file] } });

    expect(await screen.findByText('이미지를 저장소에 올리지 못했습니다. 다시 시도해 주세요.')).toBeTruthy();
    expect(screen.getByRole('img', { name: '선택한 프로젝트 로고 미리보기' }).getAttribute('src'))
      .toBe('blob:project-logo');
    expect((screen.getByRole('button', { name: '변경 저장' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('이미지 제거는 저장된 URL 을 비우고 저장을 활성화한다', async () => {
    __setOwnedProjectForTests({ thumbnailUrl: 'https://cdn.example/logo.webp' });
    renderPage();
    expect(await screen.findByRole('img', { name: '현재 프로젝트 로고' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '이미지 제거' }));

    expect(await screen.findByText('이미지를 제거했습니다 — 변경 저장을 눌러야 적용됩니다.')).toBeTruthy();
    expect(screen.queryByRole('img')).toBeNull();
    expect((screen.getByRole('button', { name: '변경 저장' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('이미지가 없으면 제거 버튼이 없다', async () => {
    renderPage();
    await screen.findByLabelText('프로젝트 로고 이미지 선택');

    expect(screen.queryByRole('button', { name: '이미지 제거' })).toBeNull();
  });
});
