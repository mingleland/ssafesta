// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
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

  it('선택 파일은 로컬 preview만 만들고 저장 dirty로 취급하지 않는다', async () => {
    renderPage();
    const input = await screen.findByLabelText('프로젝트 로고 이미지 선택');
    const file = new File(['logo'], 'team-logo.png', { type: 'image/png' });

    fireEvent.change(input, { target: { files: [file] } });

    expect(screen.getByRole('img', { name: '선택한 프로젝트 로고 미리보기' }).getAttribute('src'))
      .toBe('blob:project-logo');
    expect(screen.getByText('team-logo.png은 로컬 미리보기이며 아직 저장되지 않습니다.')).toBeTruthy();
    expect((screen.getByRole('button', { name: '변경 저장' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
