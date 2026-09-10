// @vitest-environment jsdom
// S15P21A604-595 — 타일 팔레트가 개수를 16으로 하드코딩해서 8×8(64칸) 타일셋의 앞 16칸
// 만 선택할 수 있었다. 선택된 타일셋의 실제 칸 수(columns×rows)만큼 노출하도록 고친 것을
// GameStudioShell을 마운트해 확인한다. 타일 배치·저장(paintTiles)이나 tileBackgroundStyle
// 크롭 자체는 authoringCommands.test.ts / 기존 렌더러 테스트가 검증하므로, 여기서는 팔레트
// 버튼 개수와 16번 이후 타일 선택만 본다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const GAME_ID = 595;

const openTilePalette = () => {
  render(
    <MemoryRouter>
      <GameStudioShell assetRepository={null} gameId={GAME_ID} initialProject={createStarterProject(GAME_ID)} publisher={null} repository={null} />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: '타일맵' }));
  fireEvent.click(screen.getByRole('button', { name: '+ 레이어' }));
};

const tileButtons = () => screen.getAllByRole('button', { name: /^타일 \d+$/ });

describe('GameStudioShell — 타일 팔레트 전체 노출(S15P21A604-595)', () => {
  it('8×8 빌트인 타일셋의 64칸을 모두 팔레트 버튼으로 노출한다', () => {
    openTilePalette();
    expect(tileButtons()).toHaveLength(64);
    expect(screen.getByRole('button', { name: '타일 63' })).toBeTruthy();
  });

  it('16번 이후 타일(40번)을 선택하면 활성 상태가 된다', () => {
    openTilePalette();
    const tile40 = screen.getByRole('button', { name: '타일 40' });
    expect(tile40.classList.contains('is-active')).toBe(false);
    fireEvent.click(tile40);
    expect((screen.getByRole('button', { name: '타일 40' }) as HTMLButtonElement).classList.contains('is-active')).toBe(true);
  });

  it('지우개 버튼은 팔레트 스크롤 영역 밖에 별도로 있다', () => {
    openTilePalette();
    const eraser = screen.getByRole('button', { name: '지우개' });
    expect(eraser.classList.contains('gss-tile-eraser')).toBe(true);
    expect(eraser.closest('.gss-tile-palette')).toBeNull();
  });
});
