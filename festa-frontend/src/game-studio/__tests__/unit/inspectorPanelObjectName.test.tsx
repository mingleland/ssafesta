// @vitest-environment jsdom
// S15P21A604-529 — InspectorPanel의 OBJECT 뷰에 추가한 "오브젝트 이름"/"플레이 중 이름
// 표시" 배선을 실제 컴포넌트를 마운트해서 확인한다. renameObject/setObjectNameVisible
// 자체(트림, PLAYER_SPAWN 무시 등)는 authoringCommands.test.ts가 이미 모델 레벨에서
// 검증했으므로, 여기서는 그 배선(입력 → 커밋 → onApply, 체크박스 토글, PLAYER_SPAWN일 때
// UI 자체가 없는지)만 확인한다.
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

const GAME_ID = 529;

const buildProject = (): { project: GameProject; objectId: string } => {
  const placed = addObject(createStarterProject(GAME_ID), 'library', 'NPC', { x: 3, y: 3 });
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

describe('InspectorPanel — 오브젝트 이름/표시 설정(S15P21A604-529)', () => {
  it('이름을 입력하면 커밋되고, 비우면 지워진다', () => {
    const { project, objectId } = buildProject();
    setup(objectId, project);

    const nameInput = screen.getByLabelText('오브젝트 이름') as HTMLInputElement;
    fireEvent.change(nameInput, { target: { value: '사서' } });
    fireEvent.blur(nameInput);
    expect((screen.getByLabelText('오브젝트 이름') as HTMLInputElement).value).toBe('사서');

    fireEvent.change(screen.getByLabelText('오브젝트 이름'), { target: { value: '   ' } });
    fireEvent.blur(screen.getByLabelText('오브젝트 이름'));
    expect((screen.getByLabelText('오브젝트 이름') as HTMLInputElement).value).toBe('');
  });

  it('"플레이 중 이름 표시" 체크박스를 토글할 수 있다', () => {
    const { project, objectId } = buildProject();
    setup(objectId, project);

    const checkbox = screen.getByLabelText('플레이 중 이름 표시') as HTMLInputElement;
    expect(checkbox.checked).toBe(false);
    fireEvent.click(checkbox);
    expect((screen.getByLabelText('플레이 중 이름 표시') as HTMLInputElement).checked).toBe(true);
  });

  it('PLAYER_SPAWN 오브젝트를 선택하면 이름/표시 설정 UI 자체가 없다', () => {
    const project = createStarterProject(GAME_ID);
    const scene = findScene(project);
    const spawnId = scene.objects.find((object) => object.preset === 'PLAYER_SPAWN')?.id;
    if (spawnId === undefined) throw new Error('expected a PLAYER_SPAWN object');

    setup(spawnId, project);

    expect(screen.queryByLabelText('오브젝트 이름')).toBeNull();
    expect(screen.queryByLabelText('플레이 중 이름 표시')).toBeNull();
  });
});
