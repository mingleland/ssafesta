import { describe, expect, it } from 'vitest';
import { findPublishBlockers, findVariableUsageLocations, findItemUsageLocations } from '../../studio/ports/publishValidation.ts';
import { cloneMinimalGameProject } from '../fixtures/minimalGameProject.ts';

describe('GameProject publish preflight', () => {
  it('accepts builtin and server-stable Asset references', () => {
    const project = cloneMinimalGameProject();
    project.assets.push({ id: 'server_image', kind: 'IMAGE', source: 'asset://games/123/assets/99' });
    expect(findPublishBlockers(project)).toEqual([]);
  });

  it('blocks local-only replacements before a server Publish request', () => {
    const project = cloneMinimalGameProject();
    project.assets.push({ id: 'local_image', kind: 'IMAGE', source: 'asset://local/123/local_image' });
    expect(findPublishBlockers(project)).toEqual([
      expect.objectContaining({ code: 'LOCAL_ASSET', assetId: 'local_image' }),
    ]);
  });

  it('reports every editor location that uses a local-only asset', () => {
    const project = cloneMinimalGameProject();
    const keyImage = project.assets.find((asset) => asset.id === 'keyImage');
    if (keyImage === undefined) throw new Error('keyImage fixture missing');
    keyImage.source = 'asset://local/123/keyImage';

    expect(findPublishBlockers(project)[0]).toMatchObject({
      assetId: 'keyImage',
      locations: ['아이템 · 작은 열쇠', '잠긴 방 · roomKey 이미지'],
    });
  });

  it.each(['data:image/png;base64,AAA', 'blob:http://localhost/id', 'file:///tmp/a.png', 'https://temporary.example/a.png'])(
    'blocks unstable source %s',
    (source) => {
      const project = cloneMinimalGameProject();
      project.assets.push({ id: 'unstable_image', kind: 'IMAGE', source });
      expect(findPublishBlockers(project)[0]).toMatchObject({ code: 'UNSTABLE_ASSET_SOURCE' });
    },
  );

  it('blocks a project that has no rule objective or COMPLETE_GAME action', () => {
    const project = cloneMinimalGameProject();
    for (const scene of project.scenes) {
      if (scene.type !== 'DIALOGUE') continue;
      for (const node of scene.nodes) {
        for (const choice of node.choices) {
          choice.actions = choice.actions.filter((action) => action.type !== 'COMPLETE_GAME');
        }
      }
    }
    expect(findPublishBlockers(project)).toContainEqual(expect.objectContaining({ code: 'NO_COMPLETION_PATH' }));
  });
});

describe('findVariableUsageLocations(S15P21A604-562)', () => {
  it('WORLD Scene 이벤트의 조건·액션에서 쓰이는 변수는 위치를 각각 보고한다', () => {
    // fixture의 doorOpened: openDoor 이벤트가 조건으로 읽고 액션으로 쓰며, leaveRoom
    // 이벤트도 조건으로 읽는다 — 같은 Scene 안 서로 다른 이벤트라도 위치 문자열은 Scene
    // 단위라 조건 쪽 하나로 합쳐진다(findAssetUsageLocations와 동일한 중복 제거 방식).
    const project = cloneMinimalGameProject();
    expect(findVariableUsageLocations(project, 'doorOpened')).toEqual([
      '잠긴 방 · 이벤트 조건',
      '잠긴 방 · 이벤트 액션',
    ]);
  });

  it('사용되지 않는 변수는 빈 배열을 반환한다', () => {
    const project = cloneMinimalGameProject();
    project.variables.push({ id: 'unused', type: 'BOOLEAN', initialValue: false });
    expect(findVariableUsageLocations(project, 'unused')).toEqual([]);
  });

  it('DIALOGUE Scene 선택지의 조건·액션에서 쓰이는 변수도 찾아낸다', () => {
    const project = cloneMinimalGameProject();
    project.variables.push({ id: 'hintSeen', type: 'BOOLEAN', initialValue: false });
    const dialogueScene = project.scenes.find((scene) => scene.id === 'doorHint');
    if (dialogueScene?.type !== 'DIALOGUE') throw new Error('doorHint fixture missing');
    dialogueScene.nodes[0]!.choices[0]!.conditions = [{ type: 'VARIABLE_EQUALS', variableId: 'hintSeen', value: false }];
    dialogueScene.nodes[0]!.choices[0]!.actions = [
      { type: 'SET_VARIABLE', variableId: 'hintSeen', value: true },
      ...dialogueScene.nodes[0]!.choices[0]!.actions,
    ];

    expect(findVariableUsageLocations(project, 'hintSeen')).toEqual([
      '문 열림 안내 · 안내 선택지 조건',
      '문 열림 안내 · 안내 선택지 액션',
    ]);
  });
});

describe('findItemUsageLocations(S15P21A604-565)', () => {
  it('오브젝트의 PICKUP 컴포넌트, 이벤트 조건·액션에서 쓰이는 아이템을 모두 찾는다', () => {
    // fixture의 key: roomKey 오브젝트가 PICKUP으로 들고, takeKey 이벤트가 GIVE_ITEM
    // 액션으로, openDoor 이벤트가 HAS_ITEM 조건으로 참조한다 — 변수/자산과 달리
    // 오브젝트 컴포넌트까지 훑어야 하는 게 이 함수만의 차이점이다.
    const project = cloneMinimalGameProject();
    expect(findItemUsageLocations(project, 'key')).toEqual([
      '잠긴 방 · roomKey 획득 오브젝트',
      '잠긴 방 · 이벤트 액션',
      '잠긴 방 · 이벤트 조건',
    ]);
  });

  it('사용되지 않는 아이템은 빈 배열을 반환한다', () => {
    const project = cloneMinimalGameProject();
    project.items.push({ id: 'unused', name: '안 쓰는 아이템' });
    expect(findItemUsageLocations(project, 'unused')).toEqual([]);
  });

  it('DIALOGUE Scene 선택지의 조건·액션에서 쓰이는 아이템도 찾아낸다', () => {
    const project = cloneMinimalGameProject();
    project.items.push({ id: 'ticket', name: '입장권' });
    const dialogueScene = project.scenes.find((scene) => scene.id === 'doorHint');
    if (dialogueScene?.type !== 'DIALOGUE') throw new Error('doorHint fixture missing');
    dialogueScene.nodes[0]!.choices[0]!.conditions = [{ type: 'HAS_ITEM', itemId: 'ticket' }];
    dialogueScene.nodes[0]!.choices[0]!.actions = [
      { type: 'REMOVE_ITEM', itemId: 'ticket' },
      ...dialogueScene.nodes[0]!.choices[0]!.actions,
    ];

    expect(findItemUsageLocations(project, 'ticket')).toEqual([
      '문 열림 안내 · 안내 선택지 조건',
      '문 열림 안내 · 안내 선택지 액션',
    ]);
  });
});
