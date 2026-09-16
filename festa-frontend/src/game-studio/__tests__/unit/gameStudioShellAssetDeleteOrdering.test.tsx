// @vitest-environment jsdom
// S15P21A604-709 — deleteAsset()이 서버 삭제 성공 뒤에만 로컬 참조를 지우는지 확인한다.
// 원래는 로컬을 먼저 지우고 서버 실패를 되돌리지 않아서, 서버 DELETE endpoint가 없던 동안
// 화면에서는 자산이 사라졌는데 서버·스토리지엔 그대로 남는 상태 불일치가 실제로 있었다
// (조사 기록 참고). 그래서 여기서는 실패 시 "화면에 그대로 남아있는지"를 직접 확인한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { addAssetReference } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameAssetRepository } from '../../studio/assets/localAssetRepository.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

const GAME_ID = 709;

// 첫 방문 안내 모달이 role="dialog"로 뜨면서 삭제 확인 카드와 role이 겹친다 —
// 미리 "봤음"으로 표시해 이 테스트의 관심사(삭제 확인 카드)만 남긴다.
beforeEach(() => {
  window.localStorage.setItem(`festa.game-studio.onboarding.v3.${GAME_ID}`, 'done');
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.localStorage.clear();
});

const projectWithCustomAsset = (): GameProject => addAssetReference(createStarterProject(GAME_ID), {
  id: 'myPortrait',
  kind: 'IMAGE',
  source: `asset://game/${GAME_ID}/myPortrait`,
  label: 'my-portrait.png',
});

const createMockRepository = (deleteImpl: GameAssetRepository['delete']): GameAssetRepository => ({
  save: async () => { throw new Error('not used in this test'); },
  resolve: async () => null,
  delete: deleteImpl,
});

const openDataTabWithAsset = (repository: GameAssetRepository) => {
  const utils = render(
    <MemoryRouter>
      <GameStudioShell
        assetRepository={repository}
        gameId={GAME_ID}
        initialProject={projectWithCustomAsset()}
        publisher={null}
        repository={null}
      />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: '데이터' }));
  return utils;
};

const confirmDelete = () => {
  fireEvent.click(screen.getByRole('button', { name: /내 자산 · my-portrait\.png 삭제/ }));
  const confirmCard = screen.getByRole('dialog');
  fireEvent.click(within(confirmCard).getByRole('button', { name: '삭제' }));
};

describe('GameStudioShell — 자산 삭제 순서(S15P21A604-709)', () => {
  it('서버 삭제가 실패하면 화면에서 자산을 지우지 않고 오류만 안내한다', async () => {
    const deleteSpy = vi.fn(async () => { throw new Error('사용 중인 자산입니다.'); });
    openDataTabWithAsset(createMockRepository(deleteSpy));

    confirmDelete();

    await screen.findByText('사용 중인 자산입니다.');
    expect(deleteSpy).toHaveBeenCalledTimes(1);
    // 실패했으므로 화면에 그대로 남아있어야 한다 — 이게 709에서 고친 순서 버그의 핵심 회귀 지점.
    expect(screen.getByRole('button', { name: /내 자산 · my-portrait\.png 삭제/ })).not.toBeNull();
  });

  it('서버 삭제가 성공하면 그때 화면에서도 사라진다', async () => {
    const deleteSpy = vi.fn(async () => undefined);
    openDataTabWithAsset(createMockRepository(deleteSpy));

    confirmDelete();

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /내 자산 · my-portrait\.png 삭제/ })).toBeNull();
    });
    expect(deleteSpy).toHaveBeenCalledTimes(1);
  });
});
