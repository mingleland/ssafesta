// @vitest-environment jsdom
// S15P21A604-566 — 데이터 탭에 "+ Boolean 변수"만 있고 "+ Integer 변수"/"+ String 변수"가
// 없던 공백을 메운다. GAME GOALS 섹션의 3버튼 패턴을 그대로 따른다.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProjectDataPanel } from '../../studio/ui/ProjectDataPanel.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 570;

const setup = (project: GameProject) => {
  const onApply = vi.fn();
  render(
    <ProjectDataPanel
      onApply={onApply}
      onDeleteAsset={() => undefined}
      onDeleteItem={() => undefined}
      onDeleteVariable={() => undefined}
      onUploadAsset={() => undefined}
      project={project}
    />,
  );
  return { onApply };
};

describe('ProjectDataPanel — Integer·String 변수 생성(S15P21A604-566)', () => {
  it('"+ Integer" 클릭 시 INTEGER 타입 변수를 추가하는 프로젝트로 onApply가 호출된다', () => {
    const project = createStarterProject(GAME_ID);
    const { onApply } = setup(project);

    fireEvent.click(screen.getByRole('button', { name: '+ Integer' }));

    expect(onApply).toHaveBeenCalledTimes(1);
    const applied = onApply.mock.calls[0]![0] as GameProject;
    const created = applied.variables[applied.variables.length - 1]!;
    expect(created.type).toBe('INTEGER');
    expect(created.initialValue).toBe(0);
  });

  it('"+ String" 클릭 시 STRING 타입 변수를 추가하는 프로젝트로 onApply가 호출된다', () => {
    const project = createStarterProject(GAME_ID);
    const { onApply } = setup(project);

    fireEvent.click(screen.getByRole('button', { name: '+ String' }));

    expect(onApply).toHaveBeenCalledTimes(1);
    const applied = onApply.mock.calls[0]![0] as GameProject;
    const created = applied.variables[applied.variables.length - 1]!;
    expect(created.type).toBe('STRING');
    expect(created.initialValue).toBe('');
  });

  it('변수 100개 상한에서 세 버튼 모두 비활성화된다', () => {
    const project = createStarterProject(GAME_ID);
    const full: GameProject = {
      ...project,
      // 스타터 프로젝트 기본 변수(doorOpened, 이벤트에서 참조 중)는 남겨두고 100개가
      // 될 때까지만 채운다 — 통째로 갈아치우면 기존 참조가 깨져 검증에서 막힌다.
      variables: [
        ...project.variables,
        ...Array.from({ length: 100 - project.variables.length }, (_, index) => ({
          id: `filler${index}`,
          type: 'BOOLEAN' as const,
          initialValue: false,
        })),
      ],
    };
    setup(full);

    expect((screen.getByRole('button', { name: '+ Boolean' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: '+ Integer' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: '+ String' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
