// @vitest-environment jsdom
// S15P21A604-582 — 삭제 확인 UI가 스타일 없이 각 섹션 맨 끝에 인라인으로 떠서 클릭한
// 카드와 동떨어져 보이던 문제(Notion QA #56)를 중앙 모달로 바꿨다. 여기서는 모달 공통
// 동작(backdrop 클릭·Escape로 취소, aria-modal)만 확인한다 — 변수/아이템/자산별 삭제
// 흐름 자체는 projectDataPanel{Variable,Item,Asset}Delete.test.tsx가 이미 검증한다.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProjectDataPanel } from '../../studio/ui/ProjectDataPanel.tsx';
import { addBooleanVariable } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 582;

const setup = (project: GameProject) => {
  const onDeleteVariable = vi.fn();
  render(
    <ProjectDataPanel
      onApply={() => undefined}
      onDeleteAsset={() => undefined}
      onDeleteItem={() => undefined}
      onDeleteVariable={onDeleteVariable}
      onUploadAsset={() => undefined}
      project={project}
    />,
  );
  return { onDeleteVariable };
};

const openConfirm = () => {
  const project = addBooleanVariable(createStarterProject(GAME_ID));
  const newVariableId = project.variables[project.variables.length - 1]!.id;
  const ctx = setup(project);
  fireEvent.click(screen.getByRole('button', { name: `${newVariableId} 삭제` }));
  return ctx;
};

describe('ProjectDataPanel — 삭제 확인 모달(S15P21A604-582)', () => {
  it('중앙 모달로 뜬다 — role="dialog" + aria-modal="true"', () => {
    openConfirm();
    const dialog = screen.getByRole('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(document.querySelector('.gss-guide-backdrop')).toBeTruthy();
  });

  it('backdrop(바깥)를 누르면 삭제하지 않고 닫힌다', () => {
    const { onDeleteVariable } = openConfirm();
    const backdrop = document.querySelector('.gss-guide-backdrop')!;
    fireEvent.mouseDown(backdrop);
    expect(onDeleteVariable).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('모달 안쪽을 눌러도 닫히지 않는다', () => {
    openConfirm();
    fireEvent.mouseDown(screen.getByRole('dialog'));
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('Escape 키로 삭제하지 않고 닫힌다', () => {
    const { onDeleteVariable } = openConfirm();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onDeleteVariable).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('삭제 버튼은 gss-delete-confirm-danger 클래스로 위험 동작임을 표시한다', () => {
    openConfirm();
    const deleteButton = screen.getByRole('button', { name: '삭제' }) as HTMLButtonElement;
    expect(deleteButton.classList.contains('gss-delete-confirm-danger')).toBe(true);
  });
});
