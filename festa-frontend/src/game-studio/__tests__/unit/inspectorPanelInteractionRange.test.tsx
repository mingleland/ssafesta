// @vitest-environment jsdom
// S15P21A604-534 — InspectorPanel의 INTERACTABLE 컴포넌트 편집 블록에 추가한 "상호작용
// 거리" 입력 배선을 실제 컴포넌트를 마운트해서 확인한다. 거리 판정 로직(맨해튼 거리,
// range 기본값 1) 자체는 referenceRuntime.test.ts가 이미 순수 함수 레벨에서 검증했으므로,
// 여기서는 입력 → onApply 반영, 그리고 "안내 문구를 고쳐도 range가 사라지지 않는지"만 본다
// (처음에 { type: 'INTERACTABLE', prompt }로 새로 합성해서 range를 조용히 날리는 실수를
// 했었다 — 그 회귀를 여기서 잡는다).
import { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { InspectorPanel } from '../../studio/ui/InspectorPanel.tsx';
import { addObject } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import type { GameObject, GameProject, WorldScene } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 534;

// addObject(..., 'INTERACTABLE', ...)는 authoringRegistry.ts의 프리셋 정의에 따라
// INTERACTABLE 컴포넌트(prompt: '조사하기')를 기본으로 달고 나온다 — "고급 설정"을
// 열지 않아도 이 컴포넌트 카드가 바로 보인다(INTERACTABLE은 COMPONENT_TYPES의 필수
// 항목이 아니라 프리셋 기본 컴포넌트다).
const buildProject = (): { project: GameProject; objectId: string } => {
  const placed = addObject(createStarterProject(GAME_ID), 'library', 'INTERACTABLE', { x: 3, y: 3 });
  return { project: placed.project, objectId: placed.objectId };
};

const findScene = (project: GameProject): WorldScene => {
  const scene = project.scenes.find((candidate) => candidate.id === 'library');
  if (scene?.type === 'DIALOGUE' || scene === undefined) throw new Error('expected library world scene');
  return scene;
};

const findObject = (project: GameProject, objectId: string): GameObject | null => (
  findScene(project).objects.find((candidate) => candidate.id === objectId) ?? null
);

const Harness = ({ initialProject, objectId }: { initialProject: GameProject; objectId: string }) => {
  const [project, setProject] = useState(initialProject);
  const scene = findScene(project);
  const selectedObject = findObject(project, objectId);
  return (
    <InspectorPanel
      assetUrls={{}}
      onApply={setProject}
      onObjectRemoved={() => undefined}
      onReplaceSprite={() => undefined}
      project={project}
      scene={scene}
      selectedObject={selectedObject}
    />
  );
};

const setup = (objectId: string, project: GameProject) => render(
  <Harness initialProject={project} objectId={objectId} />,
);

describe('InspectorPanel — 상호작용 거리 설정(S15P21A604-534)', () => {
  it('아무것도 안 건드리면 거리 입력칸은 기본값 1을 보여준다', () => {
    const { project, objectId } = buildProject();
    setup(objectId, project);
    expect((screen.getByLabelText('상호작용 거리') as HTMLInputElement).value).toBe('1');
  });

  it('거리를 입력하면 그 값이 오브젝트의 INTERACTABLE 컴포넌트에 저장된다', () => {
    const { project, objectId } = buildProject();
    setup(objectId, project);

    fireEvent.change(screen.getByLabelText('상호작용 거리'), { target: { value: '5' } });

    // InspectorPanel은 controlled input(value={component.range ?? 1})이라, onApply로
    // 반영된 뒤 다시 그려진 입력값이 곧 실제 저장된 값이다.
    expect((screen.getByLabelText('상호작용 거리') as HTMLInputElement).value).toBe('5');
  });

  it('안내 문구를 고쳐도 이미 설정한 거리가 사라지지 않는다', () => {
    const { project, objectId } = buildProject();
    setup(objectId, project);

    fireEvent.change(screen.getByLabelText('상호작용 거리'), { target: { value: '7' } });
    const promptInput = screen.getByLabelText('상호작용 안내 문구');
    fireEvent.change(promptInput, { target: { value: '자물쇠 살펴보기' } });
    fireEvent.blur(promptInput);

    expect((screen.getByLabelText('상호작용 거리') as HTMLInputElement).value).toBe('7');
    expect((screen.getByLabelText('상호작용 안내 문구') as HTMLInputElement).value).toBe('자물쇠 살펴보기');
  });
});
