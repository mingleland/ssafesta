// @vitest-environment jsdom
// S15P21A604-494 — DialogueEditor의 NODES rail에 씬 목록과 동일한 패턴(드래그 정렬 핸들·
// "시작으로 설정"·삭제)을 추가한 것을 실제 컴포넌트를 마운트해서 검증한다. 모델 레벨 함수
// (reorderDialogueNode/startNodeChangeReason·setStartNode/dialogueNodeRemovalReason·
// removeDialogueNode) 자체는 authoringCommands.test.ts가 이미 검증하므로, 여기서는 그
// 배선(드래그 이벤트 → 실제 호출, 버튼 disabled/title)만 확인한다.
import { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DialogueEditor } from '../../studio/ui/DialogueEditor.tsx';
import { addDialogueNode, addDialogueScene } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { parseGameProject, type DialogueScene, type GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 494;

const buildProject = (): { project: GameProject; sceneId: string } => {
  let project = addDialogueScene(parseGameProject(createStarterProject(GAME_ID)), 'OVERLAY');
  const sceneId = project.scenes.at(-1)?.id;
  if (sceneId === undefined) throw new Error('expected a newly added DIALOGUE scene');
  const second = addDialogueNode(project, sceneId);
  project = second.project;
  const third = addDialogueNode(project, sceneId);
  project = third.project;
  return { project, sceneId };
};

// DialogueEditor는 onApply로만 상위에 변경을 알리는 controlled 컴포넌트라, 실제 반영된
// project를 테스트에서 확인할 수 있도록 최소한의 상태 하네스로 감싼다. 노드 순서/시작
// 노드는 DOM 텍스트만으로 구분하기 어려워(기본 노드들이 전부 같은 텍스트) 숨은 요약을
// data-testid로 노출한다.
const Harness = ({ initialProject, sceneId }: { initialProject: GameProject; sceneId: string }) => {
  const [project, setProject] = useState(initialProject);
  const scene = project.scenes.find((candidate) => candidate.id === sceneId) as DialogueScene;
  return (
    <>
      <div data-testid="node-order">{scene.nodes.map((node) => node.id).join(',')}</div>
      <div data-testid="start-node">{scene.startNodeId}</div>
      <DialogueEditor assetUrls={{}} onApply={setProject} project={project} scene={scene} />
    </>
  );
};

const setup = () => {
  const { project, sceneId } = buildProject();
  const { container } = render(<Harness initialProject={project} sceneId={sceneId} />);
  return { container };
};

describe('DialogueEditor — 노드 정렬/시작 설정/삭제(S15P21A604-494)', () => {
  it('드래그(햄버거 핸들)로 노드 순서를 바꿀 수 있다', () => {
    const { container } = setup();
    const before = screen.getByTestId('node-order').textContent?.split(',') ?? [];
    expect(before).toHaveLength(3);

    const rows = container.querySelectorAll('.gss-node-row');
    const firstHandle = rows[0]?.querySelector('.gss-drag-handle') as HTMLElement;
    const thirdRow = rows[2] as HTMLElement;

    fireEvent.dragStart(firstHandle);
    fireEvent.dragOver(thirdRow);
    fireEvent.drop(thirdRow);

    const after = screen.getByTestId('node-order').textContent?.split(',') ?? [];
    expect(after).toEqual([before[1], before[2], before[0]]);
  });

  it('"시작으로 설정" 버튼을 누르면 그 노드가 새 START가 되고, 이미 START인 노드는 버튼이 비활성화된다', () => {
    const { container } = setup();
    const startBefore = screen.getByTestId('node-order').textContent?.split(',')[0];
    expect(screen.getByTestId('start-node').textContent).toBe(startBefore);

    const rows = container.querySelectorAll('.gss-node-row');
    const firstStartButton = rows[0]?.querySelector('[aria-label="1번째 노드를 시작으로 설정"]') as HTMLButtonElement;
    expect(firstStartButton.disabled).toBe(true);

    const secondStartButton = rows[1]?.querySelector('[aria-label="2번째 노드를 시작으로 설정"]') as HTMLButtonElement;
    expect(secondStartButton.disabled).toBe(false);
    fireEvent.click(secondStartButton);

    const nodeOrder = screen.getByTestId('node-order').textContent?.split(',') ?? [];
    expect(screen.getByTestId('start-node').textContent).toBe(nodeOrder[1]);
  });

  it('일반 노드는 삭제되고, START 노드의 삭제 버튼은 비활성화된 채 이유가 붙는다', () => {
    const { container } = setup();
    const rows = container.querySelectorAll('.gss-node-row');

    const firstRemoveButton = rows[0]?.querySelector('[aria-label="1번째 노드 삭제"]') as HTMLButtonElement;
    expect(firstRemoveButton.disabled).toBe(true);
    expect(firstRemoveButton.title).toBe('시작 노드는 삭제할 수 없습니다. 다른 노드를 시작으로 설정한 뒤 삭제하세요.');

    const thirdRemoveButton = rows[2]?.querySelector('[aria-label="3번째 노드 삭제"]') as HTMLButtonElement;
    expect(thirdRemoveButton.disabled).toBe(false);
    fireEvent.click(thirdRemoveButton);

    expect(screen.getByTestId('node-order').textContent?.split(',')).toHaveLength(2);
  });
});
