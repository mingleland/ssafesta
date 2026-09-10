// @vitest-environment jsdom
// S15P21A604-554 — InspectorPanel의 숫자 입력칸(발사 간격·생성 간격 등)이 실제로
// NumberCommitInput으로 배선됐는지, 특히 최소값이 2자리 이상인 필드(발사 간격 100,
// 생성 간격 250)에서 "지우고 중간값을 거쳐 다시 타이핑"이 실제로 가능한지를 InspectorPanel
// 자체를 마운트해서 확인한다. NumberCommitInput 자체의 단위 동작(clamp 계산, Escape 등)은
// numberCommitInput.test.tsx가 이미 검증했으므로, 여기서는 "InspectorPanel이 그 컴포넌트를
// 올바른 min/max·onCommit으로 실제로 꽂아뒀는지"만 본다.
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

const GAME_ID = 554;

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

const setup = (preset: 'TURRET' | 'SPAWNER') => {
  const placed = addObject(createStarterProject(GAME_ID), 'library', preset, { x: 3, y: 3 });
  render(<Harness initialProject={placed.project} objectId={placed.objectId} />);
};

describe('InspectorPanel — 숫자 입력칸 clamp-on-blur 배선(S15P21A604-554)', () => {
  it('발사 간격(최소 100)을 지우고 "1","10"을 거쳐 "150"까지 타이핑해도 중간에 되돌아가지 않는다', () => {
    setup('TURRET');
    const input = screen.getByLabelText('발사 간격 ms') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '1' } });
    expect(input.value).toBe('1');
    fireEvent.change(input, { target: { value: '15' } });
    expect(input.value).toBe('15');
    fireEvent.change(input, { target: { value: '150' } });
    expect(input.value).toBe('150');
    fireEvent.blur(input);
    expect(input.value).toBe('150');
  });

  it('발사 간격에 최소값(100)보다 작은 값을 남긴 채 blur하면 100으로 보정된다', () => {
    setup('TURRET');
    const input = screen.getByLabelText('발사 간격 ms') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '5' } });
    fireEvent.blur(input);
    expect(input.value).toBe('100');
  });

  it('생성 간격(최소 250)을 지우고 "2","25"를 거쳐 "2500"까지 타이핑해도 중간에 되돌아가지 않는다', () => {
    setup('SPAWNER');
    const input = screen.getByLabelText('생성 간격 ms') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '2' } });
    expect(input.value).toBe('2');
    fireEvent.change(input, { target: { value: '25' } });
    expect(input.value).toBe('25');
    fireEvent.change(input, { target: { value: '2500' } });
    expect(input.value).toBe('2500');
    fireEvent.blur(input);
    expect(input.value).toBe('2500');
  });

  it('생성 간격에 최대값(60000)보다 큰 값을 남긴 채 blur하면 60000으로 보정된다', () => {
    setup('SPAWNER');
    const input = screen.getByLabelText('생성 간격 ms') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '999999' } });
    fireEvent.blur(input);
    expect(input.value).toBe('60000');
  });
});
