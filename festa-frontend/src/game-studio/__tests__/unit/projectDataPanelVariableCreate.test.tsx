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

    // 이 케이스에서만 getByText 로 찾는다 (GitLab #168). getByRole 은 DOM 을 순회하며 요소마다
    // role 과 accessible name 을 계산하는데, 변수 100개가 그려진 상태에서는 그 비용이 렌더보다
    // 크다 — 실측 median: 렌더 64.0ms · getByRole x3 138.2ms(p95 240.6) · getByText x3 51.5ms.
    // 세 버튼은 텍스트를 직접 담고 있어 getByText 가 같은 button 요소를 돌려준다. 재는 대상
    // (상한에서 비활성화되는가)은 그대로이고, 무관한 쿼리 비용만 뺐다.
    // 변수가 적은 위 두 케이스는 getByRole 이 3.9ms 라 그대로 둔다.
    expect((screen.getByText('+ Boolean') as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByText('+ Integer') as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByText('+ String') as HTMLButtonElement).disabled).toBe(true);
  });
});
