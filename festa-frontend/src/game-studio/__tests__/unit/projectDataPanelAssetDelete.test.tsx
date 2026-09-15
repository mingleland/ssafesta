// @vitest-environment jsdom
// S15P21A604-561 — 데이터 탭에 없던 자산 삭제 UI. 삭제 버튼은 바로 지우지 않고 사용
// 위치를 먼저 보여준 뒤 확인을 받아야 한다(findAssetUsageLocations 재사용). 빌트인
// 자산은 프로젝트 소유가 아니라 카탈로그 참조라 삭제 버튼 자체가 없어야 한다.
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProjectDataPanel } from '../../studio/ui/ProjectDataPanel.tsx';
import { addAssetReference, setTopDownBackground } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 561;

const projectWithCustomAsset = (): GameProject => addAssetReference(createStarterProject(GAME_ID), {
  id: 'myPortrait',
  kind: 'IMAGE',
  source: `asset://local/${GAME_ID}/myPortrait`,
});

const setup = (project: GameProject) => {
  const onApply = vi.fn();
  const onDeleteAsset = vi.fn();
  render(
    <ProjectDataPanel
      onApply={onApply}
      onDeleteAsset={onDeleteAsset}
      onDeleteItem={() => undefined}
      onDeleteVariable={() => undefined}
      onUploadAsset={() => undefined}
      project={project}
    />,
  );
  return { onApply, onDeleteAsset };
};

describe('ProjectDataPanel — 자산 삭제(S15P21A604-561)', () => {
  it('빌트인 자산에는 삭제 버튼이 없다', () => {
    setup(createStarterProject(GAME_ID));
    expect(screen.queryByRole('button', { name: /^내 자산 · .+ 삭제$/ })).toBeNull();
  });

  it('사용되지 않는 커스텀 자산은 삭제 버튼 → 확인 카드 → 삭제 확정까지 이어진다', () => {
    const { onDeleteAsset } = setup(projectWithCustomAsset());

    fireEvent.click(screen.getByRole('button', { name: /내 자산 · myPortrait 삭제/ }));
    expect(screen.getByText('현재 배치에서 사용되지 않는 자산입니다.')).toBeTruthy();

    const confirmCard = screen.getByRole('dialog');
    fireEvent.click(within(confirmCard).getByRole('button', { name: '삭제' }));

    expect(onDeleteAsset).toHaveBeenCalledWith('myPortrait');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('취소를 누르면 삭제하지 않고 확인 카드만 닫는다', () => {
    const { onDeleteAsset } = setup(projectWithCustomAsset());

    fireEvent.click(screen.getByRole('button', { name: /내 자산 · myPortrait 삭제/ }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(onDeleteAsset).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('사용 중인 자산은 삭제 전에 사용 위치를 보여준다', () => {
    const base = projectWithCustomAsset();
    const project = setTopDownBackground(base, 'library', 'myPortrait');
    setup(project);

    fireEvent.click(screen.getByRole('button', { name: /내 자산 · myPortrait 삭제/ }));

    const confirmCard = screen.getByRole('dialog');
    expect(within(confirmCard).getByText('다음 위치에서 사용 중입니다 — 삭제하면 그 자리는 빈 값으로 남습니다.')).toBeTruthy();
    expect(within(confirmCard).getByText(/⌖/)).toBeTruthy();
  });
});
