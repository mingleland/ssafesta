// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../../../entities/project/api.select', async () => {
  const mockApi = await import('../../../../entities/project/api.mock');
  return { projectApi: mockApi };
});

import { __resetProjectMockForTests } from '../../../../entities/project/api.mock';
import { __resetProjectEditForTests } from '../../../../features/project/model/edit';
import { ProjectManagementPage } from '../../ProjectManagementPage';

beforeEach(() => {
  __resetProjectEditForTests();
  __resetProjectMockForTests();
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
});
