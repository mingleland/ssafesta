// @vitest-environment jsdom
// S15P21A604-529 — 이름 + "플레이 중 표시"가 모두 켜진 오브젝트만 플레이 화면에 상시
// 이름표가 뜨는지 확인한다. renameObject/setObjectNameVisible 자체(트림, PLAYER_SPAWN
// 무시 등)는 authoringCommands.test.ts가 이미 검증했으므로, 여기서는 그 값을 런타임이
// 실제로 화면에 반영하는가(조건 분기)만 본다.
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { addObject, renameObject, setObjectNameVisible } from '../../studio/model/authoringCommands.ts';
import type { GameProject } from '../../contracts/gameProject.ts';

afterEach(() => {
  cleanup();
});

const STABLE_ASSET_URLS = {};

const setup = (project: GameProject) => render(
  <ReferenceGamePlayer
    assetUrls={STABLE_ASSET_URLS}
    mode="PREVIEW"
    onExit={() => undefined}
    project={project}
    sessionPort={createPreviewGameSessionPort()}
  />,
);

describe('ReferenceGamePlayer — 오브젝트 상시 이름표(S15P21A604-529)', () => {
  it('이름을 짓고 표시를 켠 오브젝트는 이름표가 뜬다', () => {
    const placed = addObject(createStarterProject(529), 'library', 'NPC', { x: 3, y: 3 });
    let project = placed.project;
    project = renameObject(project, 'library', placed.objectId, '사서보조');
    project = setObjectNameVisible(project, 'library', placed.objectId, true);

    const { container } = setup(project);
    expect(container.querySelector('.grp-object-nameplate')?.textContent).toBe('사서보조');
  });

  it('표시를 꺼두면(기본값) 이름이 있어도 이름표가 뜨지 않는다', () => {
    const placed = addObject(createStarterProject(529), 'library', 'NPC', { x: 3, y: 3 });
    const project = renameObject(placed.project, 'library', placed.objectId, '사서보조');

    const { container } = setup(project);
    expect(container.querySelector('.grp-object-nameplate')).toBeNull();
  });

  it('표시를 켜도 이름이 없으면(기본값) 이름표가 뜨지 않는다', () => {
    const placed = addObject(createStarterProject(529), 'library', 'NPC', { x: 3, y: 3 });
    const project = setObjectNameVisible(placed.project, 'library', placed.objectId, true);

    const { container } = setup(project);
    expect(container.querySelector('.grp-object-nameplate')).toBeNull();
  });

  it('NPC 외 다른 프리셋(DOOR)에도 동일하게 동작한다', () => {
    const placed = addObject(createStarterProject(529), 'library', 'DOOR', { x: 3, y: 3 });
    let project = placed.project;
    project = renameObject(project, 'library', placed.objectId, '뒷문');
    project = setObjectNameVisible(project, 'library', placed.objectId, true);

    const { container } = setup(project);
    expect(container.querySelector('.grp-object-nameplate')?.textContent).toBe('뒷문');
  });
});
