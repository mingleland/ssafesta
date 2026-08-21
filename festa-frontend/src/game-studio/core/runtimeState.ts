import {
  findScene,
  type GameProject,
  type Scalar,
} from '../contracts/gameProject.ts';

export type RuntimeStatus = 'PLAYING' | 'COMPLETED' | 'FAILED';

export interface RuntimeFailure {
  readonly code: string;
  readonly message: string;
}

export interface RuntimeSessionState {
  readonly status: RuntimeStatus;
  readonly currentSceneId: string;
  readonly activeDialogueSceneId: string | null;
  readonly currentDialogueNodeId: string | null;
  readonly variables: Readonly<Record<string, Scalar>>;
  readonly inventory: ReadonlySet<string>;
  readonly objectVisibility: Readonly<Record<string, boolean>>;
  readonly failure: RuntimeFailure | null;
}

export const createRuntimeSessionState = (project: GameProject): RuntimeSessionState => {
  const startScene = findScene(project, project.startSceneId);
  if (startScene === undefined) {
    throw new Error(`validated project is missing start scene: ${project.startSceneId}`);
  }

  return {
    status: 'PLAYING',
    currentSceneId: startScene.id,
    activeDialogueSceneId: null,
    currentDialogueNodeId: startScene.type === 'DIALOGUE' ? startScene.startNodeId : null,
    variables: Object.freeze(Object.fromEntries(
      project.variables.map((variable) => [variable.id, variable.initialValue]),
    )),
    inventory: new Set<string>(),
    objectVisibility: Object.freeze(Object.fromEntries(
      project.scenes.flatMap((scene) => (
        scene.type === 'TOP_DOWN'
          ? scene.objects.map((object) => [object.id, object.visible] as const)
          : []
      )),
    )),
    failure: null,
  };
};

export const failRuntimeSession = (
  state: RuntimeSessionState,
  failure: RuntimeFailure,
): RuntimeSessionState => ({
  ...state,
  status: 'FAILED',
  activeDialogueSceneId: null,
  currentDialogueNodeId: null,
  failure,
});
