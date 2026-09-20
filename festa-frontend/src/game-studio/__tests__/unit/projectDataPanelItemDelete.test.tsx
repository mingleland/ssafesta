// @vitest-environment jsdom
// S15P21A604-565 — 데이터 탭에 없던 아이템 삭제 UI. -561/-562와 같은 확인 카드
// 패턴을 쓴다: 삭제 버튼은 바로 지우지 않고 사용 위치를 먼저 보여준 뒤 확인을 받는다
// (findItemUsageLocations 재사용).
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProjectDataPanel } from '../../studio/ui/ProjectDataPanel.tsx';
import { addItemDefinition } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 565;

const setup = (project: GameProject) => {
  const onApply = vi.fn();
  const onDeleteAsset = vi.fn();
  const onDeleteVariable = vi.fn();
  const onDeleteItem = vi.fn();
  render(
    <ProjectDataPanel
      onApply={onApply}
      onDeleteAsset={onDeleteAsset}
      onDeleteItem={onDeleteItem}
      onDeleteVariable={onDeleteVariable}
      onUploadAsset={() => undefined}
      project={project}
    />,
  );
  return { onApply, onDeleteAsset, onDeleteVariable, onDeleteItem };
};

describe('ProjectDataPanel — 아이템 삭제(S15P21A604-565)', () => {
  it('사용되지 않는 아이템은 삭제 버튼 → 확인 카드 → 삭제 확정까지 이어진다', () => {
    const project = addItemDefinition(createStarterProject(GAME_ID));
    const newItemId = project.items[project.items.length - 1]!.id;
    const { onDeleteItem } = setup(project);

    fireEvent.click(screen.getByRole('button', { name: `${newItemId} 삭제` }));

    const confirmCard = screen.getByRole('dialog');
    expect(within(confirmCard).getByText('현재 오브젝트·조건·액션에서 사용되지 않는 아이템입니다.')).toBeTruthy();

    fireEvent.click(within(confirmCard).getByRole('button', { name: '삭제' }));

    expect(onDeleteItem).toHaveBeenCalledWith(newItemId);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('취소를 누르면 삭제하지 않고 확인 카드만 닫는다', () => {
    const project = addItemDefinition(createStarterProject(GAME_ID));
    const newItemId = project.items[project.items.length - 1]!.id;
    const { onDeleteItem } = setup(project);

    fireEvent.click(screen.getByRole('button', { name: `${newItemId} 삭제` }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(onDeleteItem).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('사용 중인 아이템(libraryKey)은 삭제 전에 PICKUP·이벤트 사용 위치를 보여준다', () => {
    setup(createStarterProject(GAME_ID));

    fireEvent.click(screen.getByRole('button', { name: 'libraryKey 삭제' }));

    const confirmCard = screen.getByRole('dialog');
    expect(within(confirmCard).getByText('다음 위치에서 사용 중입니다 — 삭제하면 검증 오류가 날 수 있습니다.')).toBeTruthy();
    // 오브젝트 PICKUP 하나 + 이벤트 액션/조건 — 최소 두 곳 이상 나와야 한다.
    expect(within(confirmCard).getAllByText(/⌖/).length).toBeGreaterThanOrEqual(2);
  });
});
