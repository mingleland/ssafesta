import { parseGameProject, type GameProject } from '../../contracts/gameProject.ts';

export interface PublishBlocker {
  readonly code: 'LOCAL_ASSET' | 'UNSTABLE_ASSET_SOURCE' | 'NO_COMPLETION_PATH';
  readonly assetId: string;
  readonly message: string;
  readonly locations: readonly string[];
}

export const findAssetUsageLocations = (
  project: GameProject,
  assetId: string,
): readonly string[] => {
  const locations: string[] = [];
  for (const item of project.items) {
    if (item.assetId === assetId) locations.push(`아이템 · ${item.name}`);
  }
  for (const scene of project.scenes) {
    if (scene.backgroundAssetId === assetId) locations.push(`${scene.name} · 배경`);
    if (scene.type === 'DIALOGUE') {
      for (const node of scene.nodes) {
        if (node.portraitAssetId === assetId) locations.push(`${scene.name} · ${node.speaker || node.id} 초상화`);
      }
      continue;
    }
    for (const layer of scene.tileLayers) {
      if (layer.tilesetAssetId === assetId) locations.push(`${scene.name} · ${layer.name}`);
    }
    for (const object of scene.objects) {
      for (const component of object.components) {
        if (component.type === 'SPRITE' && component.assetId === assetId) locations.push(`${scene.name} · ${object.id} 이미지`);
        if (component.type === 'SHOOTER' && component.projectileAssetId === assetId) locations.push(`${scene.name} · ${object.id} 투사체`);
        if (component.type === 'SPAWNER' && component.enemyAssetId === assetId) locations.push(`${scene.name} · ${object.id} 생성 대상`);
      }
    }
  }
  return [...new Set(locations)];
};

// S15P21A604-562 — findAssetUsageLocations와 같은 모양이다. VARIABLE_EQUALS 조건과
// SET_VARIABLE 액션이 WORLD Scene의 이벤트, DIALOGUE Scene의 선택지 양쪽에 있을 수 있어
// 둘 다 훑는다. 게임 규칙(rules.completion.objectives)은 변수를 참조하지 않으니 여기선
// 안 본다.
export const findVariableUsageLocations = (
  project: GameProject,
  variableId: string,
): readonly string[] => {
  const locations: string[] = [];
  const usesVariable = (condition: { readonly type: string; readonly variableId?: string }): boolean => (
    condition.type === 'VARIABLE_EQUALS' && condition.variableId === variableId
  );
  const setsVariable = (action: { readonly type: string; readonly variableId?: string }): boolean => (
    action.type === 'SET_VARIABLE' && action.variableId === variableId
  );
  for (const scene of project.scenes) {
    if (scene.type === 'DIALOGUE') {
      for (const node of scene.nodes) {
        for (const choice of node.choices) {
          if (choice.conditions?.some(usesVariable)) locations.push(`${scene.name} · ${node.speaker || node.id} 선택지 조건`);
          if (choice.actions.some(setsVariable)) locations.push(`${scene.name} · ${node.speaker || node.id} 선택지 액션`);
        }
      }
      continue;
    }
    for (const event of scene.events) {
      if (event.conditions.some(usesVariable)) locations.push(`${scene.name} · 이벤트 조건`);
      if (event.actions.some(setsVariable)) locations.push(`${scene.name} · 이벤트 액션`);
    }
  }
  return [...new Set(locations)];
};

// S15P21A604-565 — findAssetUsageLocations/findVariableUsageLocations와 같은 모양이되,
// 아이템은 이벤트 조건·액션(HAS_ITEM/GIVE_ITEM/REMOVE_ITEM)뿐 아니라 오브젝트의 PICKUP
// 컴포넌트에서도 참조되므로 오브젝트 순회가 하나 더 필요하다. (findAssetUsageLocations의
// `project.items` 순회는 "자산 → 그 자산 쓰는 아이템" 반대 방향이라 여기선 쓰지 않는다.)
export const findItemUsageLocations = (
  project: GameProject,
  itemId: string,
): readonly string[] => {
  const locations: string[] = [];
  const usesItem = (condition: { readonly type: string; readonly itemId?: string }): boolean => (
    condition.type === 'HAS_ITEM' && condition.itemId === itemId
  );
  const changesItem = (action: { readonly type: string; readonly itemId?: string }): boolean => (
    (action.type === 'GIVE_ITEM' || action.type === 'REMOVE_ITEM') && action.itemId === itemId
  );
  for (const scene of project.scenes) {
    if (scene.type === 'DIALOGUE') {
      for (const node of scene.nodes) {
        for (const choice of node.choices) {
          if (choice.conditions?.some(usesItem)) locations.push(`${scene.name} · ${node.speaker || node.id} 선택지 조건`);
          if (choice.actions.some(changesItem)) locations.push(`${scene.name} · ${node.speaker || node.id} 선택지 액션`);
        }
      }
      continue;
    }
    for (const object of scene.objects) {
      for (const component of object.components) {
        if (component.type === 'PICKUP' && component.itemId === itemId) locations.push(`${scene.name} · ${object.id} 획득 오브젝트`);
      }
    }
    for (const event of scene.events) {
      if (event.conditions.some(usesItem)) locations.push(`${scene.name} · 이벤트 조건`);
      if (event.actions.some(changesItem)) locations.push(`${scene.name} · 이벤트 액션`);
    }
  }
  return [...new Set(locations)];
};

export const findPublishBlockers = (project: GameProject): readonly PublishBlocker[] => {
  const blockers: PublishBlocker[] = project.assets.flatMap((asset): readonly PublishBlocker[] => {
    if (asset.source.startsWith('asset://local/')) {
      return [{
        code: 'LOCAL_ASSET',
        assetId: asset.id,
        message: `${asset.id}: 내 이미지가 아직 이 브라우저에만 저장되어 있습니다. 서버 자산으로 업로드한 뒤 게시하세요.`,
        locations: findAssetUsageLocations(project, asset.id),
      }];
    }
    if (asset.source.startsWith('builtin://') || asset.source.startsWith('asset://')) return [];
    return [{
      code: 'UNSTABLE_ASSET_SOURCE',
      assetId: asset.id,
      message: `${asset.id}: 게시할 수 없는 자산 주소입니다. builtin:// 또는 서버 asset://를 사용하세요.`,
      locations: findAssetUsageLocations(project, asset.id),
    }];
  });
  const hasRuleObjective = (project.rules?.completion.objectives.length ?? 0) > 0;
  const hasCompletionAction = project.scenes.some((scene) => (
    scene.type === 'DIALOGUE'
      ? scene.nodes.some((node) => node.choices.some((choice) => choice.actions.some((action) => action.type === 'COMPLETE_GAME')))
      : scene.events.some((event) => event.actions.some((action) => action.type === 'COMPLETE_GAME'))
  ));
  if (!hasRuleObjective && !hasCompletionAction) {
    blockers.push({
      code: 'NO_COMPLETION_PATH',
      assetId: 'gameCompletion',
      message: '게임 완료 조건이 없습니다. 데이터 탭에서 목표를 추가하거나 이벤트에 “게임 완료”를 연결하세요.',
      locations: ['게임 규칙 · 완료 조건'],
    });
  }
  if (blockers.length === 0) parseGameProject(project);
  return blockers;
};
