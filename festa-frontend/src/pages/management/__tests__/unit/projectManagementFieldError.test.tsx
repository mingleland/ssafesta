// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../../../entities/project/api.select', async () => {
  const mockApi = await import('../../../../entities/project/api.mock');
  return { projectApi: mockApi };
});

import { __resetProjectMockForTests } from '../../../../entities/project/api.mock';
import { __resetProjectEditForTests } from '../../../../features/project/model/edit';
import { ProjectManagementPage } from '../../ProjectManagementPage';

// 부가 링크 토글 — 값이 있으면 펼쳐진 채로 시작하고, 사용자 토글이 그 상태를 덮어쓴다.
// 값이 있어도 토글이 죽던 버그(펼침·접힘 둘 다 변화 없음) 회귀.
async function renderWithGitUrl() {
  const { __setOwnedProjectForTests } = await import('../../../../entities/project/api.mock');
  __setOwnedProjectForTests({ gitUrl: 'https://github.com/example/ssafesta' });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/management/1/project']}>
        <Routes>
          <Route path="/management/:boothId/project" element={<ProjectManagementPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByLabelText('저장소 주소');
}

beforeEach(() => {
  __resetProjectEditForTests();
  __resetProjectMockForTests();
});

afterEach(() => {
  cleanup();
});

describe('ProjectManagementPage field error', () => {
  it('서버의 name 오류를 입력 아래와 접근성 연결로 표시하고 수정하면 지운다', async () => {
    render(
      // 저장 성공이 내 부스 관리창 조회를 무효화하므로 이 화면도 QueryClient 아래에서 산다 (-817)
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/management/1/project']}>
          <Routes>
            <Route path="/management/:boothId/project" element={<ProjectManagementPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const name = await screen.findByLabelText('프로젝트 이름');
    fireEvent.change(name, { target: { value: 'INVALID_NAME' } });
    fireEvent.click(screen.getByRole('button', { name: '변경 저장' }));

    const error = await screen.findByText('프로젝트 이름을 입력해 주세요.');
    expect(error.id).toBe('project-field-name-error');
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(name.getAttribute('aria-describedby')).toBe(error.id);
    expect(screen.queryByText('저장하지 못했습니다. 잠시 후 다시 시도해 주세요.')).toBeNull();

    fireEvent.change(name, { target: { value: '수정한 이름' } });
    await waitFor(() => expect(screen.queryByText('프로젝트 이름을 입력해 주세요.')).toBeNull());
    expect(name.getAttribute('aria-invalid')).toBeNull();
  });

  it('값이 있으면 펼쳐진 채로 시작하고 토글 한 번에 접힌다', async () => {
    await renderWithGitUrl();
    const toggle = screen.getByRole('button', { name: /저장소 · 포트폴리오 주소/ });
    expect(toggle.getAttribute('aria-expanded')).toBe('true');

    fireEvent.click(toggle);

    expect(screen.queryByLabelText('저장소 주소')).toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });

  it('접은 뒤 다시 누르면 펼쳐진다', async () => {
    await renderWithGitUrl();
    const toggle = screen.getByRole('button', { name: /저장소 · 포트폴리오 주소/ });
    fireEvent.click(toggle);
    expect(screen.queryByLabelText('저장소 주소')).toBeNull();

    fireEvent.click(toggle);

    expect(screen.getByLabelText('저장소 주소')).not.toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
  });

  it('값이 없으면 접힌 채로 시작한다', async () => {
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/management/1/project']}>
          <Routes>
            <Route path="/management/:boothId/project" element={<ProjectManagementPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByLabelText('프로젝트 이름');

    expect(screen.queryByLabelText('저장소 주소')).toBeNull();
  });
});
