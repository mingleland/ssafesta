// @vitest-environment jsdom
// S15P21A604-562 — 데이터 탭에 없던 변수 삭제 UI. -561의 자산 삭제와 같은 확인 카드
// 패턴을 쓴다: 삭제 버튼은 바로 지우지 않고 사용 위치를 먼저 보여준 뒤 확인을 받는다
// (findVariableUsageLocations 재사용).
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProjectDataPanel } from '../../studio/ui/ProjectDataPanel.tsx';
import { addBooleanVariable } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 562;

const setup = (project: GameProject) => {
  const onApply = vi.fn();
  const onDeleteAsset = vi.fn();
  const onDeleteVariable = vi.fn();
  render(
    <ProjectDataPanel
      onApply={onApply}
      onDeleteAsset={onDeleteAsset}
      onDeleteItem={() => undefined}
      onDeleteVariable={onDeleteVariable}
      onUploadAsset={() => undefined}
      project={project}
    />,
  );
  return { onApply, onDeleteAsset, onDeleteVariable };
};

describe('ProjectDataPanel — 변수 삭제(S15P21A604-562)', () => {
  it('사용되지 않는 변수는 삭제 버튼 → 확인 카드 → 삭제 확정까지 이어진다', () => {
    const project = addBooleanVariable(createStarterProject(GAME_ID));
    const newVariableId = project.variables[project.variables.length - 1]!.id;
    const { onDeleteVariable } = setup(project);

    fireEvent.click(screen.getByRole('button', { name: `${newVariableId} 삭제` }));

    const confirmCard = screen.getByRole('dialog');
    expect(within(confirmCard).getByText('현재 조건·액션에서 사용되지 않는 변수입니다.')).toBeTruthy();

    fireEvent.click(within(confirmCard).getByRole('button', { name: '삭제' }));

    expect(onDeleteVariable).toHaveBeenCalledWith(newVariableId);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('취소를 누르면 삭제하지 않고 확인 카드만 닫는다', () => {
    const project = addBooleanVariable(createStarterProject(GAME_ID));
    const newVariableId = project.variables[project.variables.length - 1]!.id;
    const { onDeleteVariable } = setup(project);

    fireEvent.click(screen.getByRole('button', { name: `${newVariableId} 삭제` }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(onDeleteVariable).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('사용 중인 변수(doorOpened)는 삭제 전에 사용 위치를 보여준다', () => {
    setup(createStarterProject(GAME_ID));

    fireEvent.click(screen.getByRole('button', { name: 'doorOpened 삭제' }));

    const confirmCard = screen.getByRole('dialog');
    expect(within(confirmCard).getByText('다음 위치에서 사용 중입니다 — 삭제하면 검증 오류가 날 수 있습니다.')).toBeTruthy();
    // library Scene의 이벤트가 조건·액션 양쪽에서 doorOpened를 참조하므로 위치가 2개다.
    expect(within(confirmCard).getAllByText(/⌖/)).toHaveLength(2);
  });
});
