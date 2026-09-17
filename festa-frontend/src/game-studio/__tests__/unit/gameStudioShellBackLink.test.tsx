// @vitest-environment jsdom
// S15P21A604-824 — 편집기 뒤로가기(‹)는 목록(/app/games)을 가리켜야 한다. 예전엔 /app/home
// (World로 리다이렉트)을 가리켰는데, 목록 화면이 생긴 뒤로는 편집 중이던 게임 목록으로
// 돌아가는 쪽이 자연스럽다 — 조용히 되돌아가지 않도록 링크 대상 자체를 고정해 둔다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('GameStudioShell — 뒤로가기 링크(S15P21A604-824)', () => {
  it('목록(/app/games)을 가리킨다', () => {
    render(
      <MemoryRouter>
        <GameStudioShell
          gameId={824}
          initialProject={createStarterProject(824)}
          publisher={null}
          repository={null}
        />
      </MemoryRouter>,
    );

    const back = screen.getByRole('link', { name: '목록으로 돌아가기' });
    expect(back.getAttribute('href')).toBe('/app/games');
  });
});
