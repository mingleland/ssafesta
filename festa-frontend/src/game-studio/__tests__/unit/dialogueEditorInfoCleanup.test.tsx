// @vitest-environment jsdom
// S15P21A604-512 — DialogueEditor 자체 화면에 남아있던 원시 정보 노출 3곳(씬 헤더의
// scene.presentation 원시값 — 캔버스 툴바의 친숙한 라벨과 중복, LIVE PREVIEW 배지의
// 원시값, NODE 카드의 내부 node id)을 제거했는지 확인한다. 캔버스 툴바(describeSceneType)
// 자체는 GameStudioShell.tsx 쪽 관심사라 여기서는 다루지 않는다 — 이번 티켓에서 그 파일은
// 건드리지 않으므로 기존 GameStudioShell 테스트가 회귀를 잡아준다.
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DialogueEditor } from '../../studio/ui/DialogueEditor.tsx';
import { addDialogueScene } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { parseGameProject, type DialogueScene, type GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const GAME_ID = 512;

const buildProject = (presentation: 'OVERLAY' | 'FULL_SCREEN'): { project: GameProject; sceneId: string } => {
  const project = addDialogueScene(parseGameProject(createStarterProject(GAME_ID)), presentation);
  const sceneId = project.scenes.at(-1)?.id;
  if (sceneId === undefined) throw new Error('expected a newly added DIALOGUE scene');
  return { project, sceneId };
};

const setup = (presentation: 'OVERLAY' | 'FULL_SCREEN') => {
  const { project, sceneId } = buildProject(presentation);
  const scene = project.scenes.find((candidate) => candidate.id === sceneId) as DialogueScene;
  const { container } = render(
    <DialogueEditor assetUrls={{}} onApply={() => undefined} project={project} scene={scene} />,
  );
  return { container, scene };
};

describe('DialogueEditor — 정보 중복/불필요 표시 정리(S15P21A604-512)', () => {
  it.each(['OVERLAY', 'FULL_SCREEN'] as const)('씬 헤더에 원시 presentation 값(%s)이 더 이상 표시되지 않는다', (presentation) => {
    const { container } = setup(presentation);
    const header = container.querySelector('.gss-dialogue-scene-header');
    expect(header?.textContent).not.toContain(presentation);
    expect(container.querySelector('.gss-dialogue-scene-header .gss-type-badge')).toBeNull();
  });

  it.each(['OVERLAY', 'FULL_SCREEN'] as const)('LIVE PREVIEW 배지에 원시 presentation 값(%s)이 더 이상 표시되지 않는다', (presentation) => {
    const { container } = setup(presentation);
    expect(container.querySelector('.gss-preview-badge')?.textContent).not.toContain(presentation);
  });

  it('NODE 카드 헤더에 내부 node id가 더 이상 노출되지 않는다', () => {
    const { container, scene } = setup('OVERLAY');
    const nodeCardHeader = container.querySelector('.gss-dialogue-node-card header');
    expect(nodeCardHeader?.textContent).not.toContain(scene.startNodeId);
  });

  it('그 외 화면 요소는 그대로 남아 있다(회귀 없음)', () => {
    // S15P21A604-513 — NODES rail이 FLOW OVERVIEW로 합쳐지면서 "NODES" 라벨 자체는
    // 더 이상 존재하지 않는다(이 파일 아래 dialogueEditorNodeManagement.test.tsx가
    // 그 통합을 별도로 검증한다). 이 회귀 테스트는 그와 무관한 요소만 확인한다.
    setup('OVERLAY');
    expect(screen.getByText('FLOW OVERVIEW')).not.toBeNull();
    expect(screen.getByText('사용자 선택지')).not.toBeNull();
  });
});
