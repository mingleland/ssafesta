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
