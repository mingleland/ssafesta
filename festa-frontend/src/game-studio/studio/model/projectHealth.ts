import type { Action, GameProject } from '../../contracts/gameProject.ts';
import { analyzeDialogueFlow } from './dialogueFlow.ts';

export type ProjectHealthStatus = 'PASS' | 'WARNING' | 'BLOCKER';

export interface ProjectHealthCheck {
  readonly id: 'ENTRY' | 'COMPLETION' | 'INTERACTION' | 'DIALOGUE' | 'SCENE_FLOW' | 'ASSET';
  readonly status: ProjectHealthStatus;
  readonly title: string;
  readonly detail: string;
  readonly locations: readonly string[];
}

const transitionSceneIds = (actions: readonly Action[]): readonly string[] => actions.flatMap((action) => (
  action.type === 'GO_TO_SCENE' || action.type === 'SHOW_DIALOGUE' ? [action.sceneId] : []
));

const reachableSceneIds = (project: GameProject): ReadonlySet<string> => {
  const knownIds = new Set(project.scenes.map((scene) => scene.id));
  const reachable = new Set<string>();
  const queue = knownIds.has(project.startSceneId) ? [project.startSceneId] : [];
  while (queue.length > 0) {
    const sceneId = queue.shift();
    if (sceneId === undefined || reachable.has(sceneId)) continue;
    reachable.add(sceneId);
    const scene = project.scenes.find((candidate) => candidate.id === sceneId);
    if (scene === undefined) continue;
    const targets = scene.type === 'DIALOGUE'
      ? scene.nodes.flatMap((node) => node.choices.flatMap((choice) => transitionSceneIds(choice.actions)))
      : scene.events.flatMap((event) => transitionSceneIds(event.actions));
    for (const target of targets) {
      if (knownIds.has(target) && !reachable.has(target)) queue.push(target);
    }
  }
  return reachable;
};

const completionCheck = (project: GameProject): ProjectHealthCheck => {
  const hasRuleObjective = (project.rules?.completion.objectives.length ?? 0) > 0;
  const hasCompletionAction = project.scenes.some((scene) => (
    scene.type === 'DIALOGUE'
      ? scene.nodes.some((node) => node.choices.some((choice) => choice.actions.some((action) => action.type === 'COMPLETE_GAME')))
      : scene.events.some((event) => event.actions.some((action) => action.type === 'COMPLETE_GAME'))
  ));
  return hasRuleObjective || hasCompletionAction
    ? { id: 'COMPLETION', status: 'PASS', title: '끝까지 플레이할 수 있어요', detail: '완료 목표 또는 게임 완료 동작이 연결되어 있습니다.', locations: [] }
    : { id: 'COMPLETION', status: 'BLOCKER', title: '게임이 끝나는 조건이 없어요', detail: '목표를 추가하거나 이벤트·대화 선택지에 “게임 완료”를 연결하세요.', locations: ['게임 규칙 · 완료 조건'] };
};

const entryCheck = (project: GameProject): ProjectHealthCheck => {
  const startScene = project.scenes.find((scene) => scene.id === project.startSceneId);
  if (startScene?.type === 'DIALOGUE') {
    return { id: 'ENTRY', status: 'PASS', title: '스토리로 시작할 준비가 됐어요', detail: `시작 Scene은 “${startScene.name}”입니다.`, locations: [] };
  }
  const spawns = startScene?.objects.filter((object) => object.preset === 'PLAYER_SPAWN') ?? [];
  return spawns.length === 1
    ? { id: 'ENTRY', status: 'PASS', title: '시작 위치가 준비됐어요', detail: '플레이어 시작점이 한 곳에 배치되어 있습니다.', locations: [] }
    : { id: 'ENTRY', status: 'BLOCKER', title: '플레이어 시작점을 확인하세요', detail: spawns.length === 0 ? '시작 맵에 플레이어 시작점을 배치하세요.' : '시작 맵의 플레이어 시작점은 한 곳만 남겨 주세요.', locations: [startScene?.name ?? project.startSceneId] };
};

const interactionCheck = (project: GameProject): ProjectHealthCheck => {
  const unwired = project.scenes.flatMap((scene) => {
    if (scene.type === 'DIALOGUE') return [];
    const targets = new Set(scene.events.filter((event) => event.trigger.type === 'ON_INTERACT').map((event) => (
      event.trigger.type === 'ON_INTERACT' ? event.trigger.targetId : ''
    )));
    return scene.objects
      .filter((object) => object.components.some((component) => component.type === 'INTERACTABLE') && !targets.has(object.id))
      .map((object) => `${scene.name} · ${object.id}`);
  });
  return unwired.length === 0
    ? { id: 'INTERACTION', status: 'PASS', title: '상호작용이 연결되어 있어요', detail: '말 걸기와 조사 대상에 실행 동작이 있습니다.', locations: [] }
    : { id: 'INTERACTION', status: 'WARNING', title: `눌러도 반응하지 않는 오브젝트 ${unwired.length}개`, detail: '상호작용 가능 오브젝트에 ON_INTERACT 이벤트를 연결하세요.', locations: unwired };
};

const dialogueCheck = (project: GameProject): ProjectHealthCheck => {
  const findings = project.scenes.flatMap((scene) => {
    if (scene.type !== 'DIALOGUE') return [];
    const flow = analyzeDialogueFlow(scene);
    const messages: string[] = [];
    if (flow.unreachableCount > 0) messages.push(`도달 불가 ${flow.unreachableCount}`);
    if (flow.incompleteOutcomeCount > 0) messages.push(`결과 미설정 ${flow.incompleteOutcomeCount}`);
    return messages.length === 0 ? [] : [`${scene.name} · ${messages.join(', ')}`];
  });
  return findings.length === 0
    ? { id: 'DIALOGUE', status: 'PASS', title: '대화 분기가 이어져 있어요', detail: '모든 대화 노드에 도달할 수 있고 선택 후 결과가 있습니다.', locations: [] }
    : { id: 'DIALOGUE', status: 'WARNING', title: '끊긴 대화 흐름이 있어요', detail: '대화 편집기의 흐름 개요에서 도달 불가 노드와 결과 없는 선택지를 확인하세요.', locations: findings };
};

const sceneFlowCheck = (project: GameProject): ProjectHealthCheck => {
  const reachable = reachableSceneIds(project);
  const unreachable = project.scenes.filter((scene) => !reachable.has(scene.id)).map((scene) => scene.name);
  return unreachable.length === 0
    ? { id: 'SCENE_FLOW', status: 'PASS', title: '모든 Scene이 게임 흐름에 있어요', detail: '시작 지점부터 모든 Scene에 도달할 수 있습니다.', locations: [] }
    : { id: 'SCENE_FLOW', status: 'WARNING', title: `연결되지 않은 Scene ${unreachable.length}개`, detail: '이벤트 또는 대화 선택지에서 Scene 이동을 연결하세요.', locations: unreachable };
};

const assetCheck = (project: GameProject): ProjectHealthCheck => {
  const localAssets = project.assets.filter((asset) => asset.source.startsWith('asset://local/'));
  const unstableAssets = project.assets.filter((asset) => !asset.source.startsWith('builtin://') && !asset.source.startsWith('asset://'));
  const locations = [...localAssets, ...unstableAssets].map((asset) => asset.id);
  return locations.length === 0
    ? { id: 'ASSET', status: 'PASS', title: '다른 사용자에게 보일 재료예요', detail: '모든 자산이 기본 재료 또는 게시 가능한 자산입니다.', locations: [] }
    : { id: 'ASSET', status: 'BLOCKER', title: `게시할 수 없는 재료 ${locations.length}개`, detail: '내 브라우저 전용 이미지와 외부 주소를 서버 자산으로 교체하세요.', locations };
};

export const analyzeProjectHealth = (project: GameProject): readonly ProjectHealthCheck[] => [
  entryCheck(project),
  completionCheck(project),
  interactionCheck(project),
  dialogueCheck(project),
  sceneFlowCheck(project),
  assetCheck(project),
];
