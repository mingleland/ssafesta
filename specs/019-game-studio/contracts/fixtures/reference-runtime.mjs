import { validateProject } from "./validate-fixtures.mjs";

const ACTION_BUDGET = 64;
const TRANSITION_BUDGET = 8;

class RuntimeError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "RuntimeError";
    this.code = code;
  }
}

const runtimeFail = (code, message) => {
  throw new RuntimeError(code, message);
};

const sceneById = (project, sceneId) => project.scenes.find((scene) => scene.id === sceneId);

const conditionsPass = (state, conditions = []) => conditions.every((condition) => {
  if (condition.type === "VARIABLE_EQUALS") return state.variables[condition.variableId] === condition.value;
  if (condition.type === "HAS_ITEM") return state.inventory.has(condition.itemId);
  return false;
});

const createDispatchContext = () => ({ actions: 0, transitions: 0 });

const transitionTo = (project, state, sceneId, context) => {
  context.transitions += 1;
  if (context.transitions > TRANSITION_BUDGET) {
    runtimeFail("TRANSITION_DEPTH_EXCEEDED", `transition depth exceeds ${TRANSITION_BUDGET}`);
  }

  const scene = sceneById(project, sceneId);
  if (!scene) runtimeFail("SCENE_REFERENCE_NOT_FOUND", `unknown scene: ${sceneId}`);
  state.activeDialogueSceneId = null;
  state.currentSceneId = scene.id;
  state.currentDialogueNodeId = scene.type === "DIALOGUE" ? scene.startNodeId : null;
  if (scene.type === "TOP_DOWN") dispatchTopDown(project, state, "ON_SCENE_START", undefined, context);
};

const showDialogue = (project, state, sceneId) => {
  const scene = sceneById(project, sceneId);
  if (scene?.type !== "DIALOGUE" || scene.presentation !== "OVERLAY") {
    runtimeFail("DIALOGUE_PRESENTATION_INVALID", `SHOW_DIALOGUE requires OVERLAY: ${sceneId}`);
  }
  state.activeDialogueSceneId = scene.id;
  state.currentDialogueNodeId = scene.startNodeId;
};

const applyAction = (project, state, action, context) => {
  context.actions += 1;
  if (context.actions > ACTION_BUDGET) {
    runtimeFail("ACTION_BUDGET_EXCEEDED", `action count exceeds ${ACTION_BUDGET}`);
  }

  if (action.type === "SET_VARIABLE") state.variables[action.variableId] = action.value;
  else if (action.type === "GIVE_ITEM") state.inventory.add(action.itemId);
  else if (action.type === "REMOVE_ITEM") state.inventory.delete(action.itemId);
  else if (action.type === "SHOW_OBJECT") state.objectVisibility[action.objectId] = true;
  else if (action.type === "HIDE_OBJECT") state.objectVisibility[action.objectId] = false;
  else if (action.type === "SHOW_DIALOGUE") showDialogue(project, state, action.sceneId);
  else if (action.type === "CLOSE_DIALOGUE") {
    if (!state.activeDialogueSceneId) {
      runtimeFail("DIALOGUE_CLOSE_CONTEXT_INVALID", "no active OVERLAY dialogue");
    }
    state.activeDialogueSceneId = null;
    state.currentDialogueNodeId = null;
  }
  else if (action.type === "GO_TO_SCENE") transitionTo(project, state, action.sceneId, context);
  else if (action.type === "COMPLETE_GAME") state.status = "COMPLETED";
};

const applyActions = (project, state, actions, context) => {
  for (const action of actions) applyAction(project, state, action, context);
};

function dispatchTopDown(project, state, triggerType, targetId, context) {
  const scene = sceneById(project, state.currentSceneId);
  if (scene?.type !== "TOP_DOWN") runtimeFail("INPUT_NOT_ALLOWED", `${triggerType} requires TOP_DOWN scene`);

  const matchingEvents = scene.events.filter((event) => (
    event.trigger.type === triggerType
    && (triggerType === "ON_SCENE_START" || event.trigger.targetId === targetId)
  ));

  for (const event of matchingEvents) {
    if (!conditionsPass(state, event.conditions)) continue;
    applyActions(project, state, event.actions, context);
    const lastAction = event.actions.at(-1)?.type;
    if (lastAction === "SHOW_DIALOGUE" || lastAction === "GO_TO_SCENE" || lastAction === "COMPLETE_GAME") return;
  }
}

export function startRuntime(project) {
  validateProject(project);
  const state = {
    status: "PLAYING",
    currentSceneId: project.startSceneId,
    activeDialogueSceneId: null,
    currentDialogueNodeId: null,
    variables: Object.fromEntries(project.variables.map((variable) => [variable.id, variable.initialValue])),
    inventory: new Set(),
    objectVisibility: Object.fromEntries(
      project.scenes.flatMap((scene) => (scene.objects ?? []).map((object) => [object.id, object.visible])),
    ),
  };

  const startScene = sceneById(project, state.currentSceneId);
  if (startScene.type === "DIALOGUE") state.currentDialogueNodeId = startScene.startNodeId;
  else dispatchTopDown(project, state, "ON_SCENE_START", undefined, createDispatchContext());
  return state;
}

export function applyRuntimeInput(project, state, input) {
  if (state.status !== "PLAYING") runtimeFail("INPUT_NOT_ALLOWED", `runtime status is ${state.status}`);
  const context = createDispatchContext();

  if (input.type === "ENTER" || input.type === "INTERACT") {
    if (state.objectVisibility[input.targetId] === false) {
      runtimeFail("INPUT_TARGET_HIDDEN", `target is hidden: ${input.targetId}`);
    }
    dispatchTopDown(
      project,
      state,
      input.type === "ENTER" ? "ON_ENTER" : "ON_INTERACT",
      input.targetId,
      context,
    );
    return state;
  }

  if (input.type === "CHOOSE") {
    const dialogueSceneId = state.activeDialogueSceneId ?? state.currentSceneId;
    const scene = sceneById(project, dialogueSceneId);
    if (scene?.type !== "DIALOGUE") runtimeFail("INPUT_NOT_ALLOWED", "CHOOSE requires DIALOGUE scene");
    const node = scene.nodes.find((candidate) => candidate.id === state.currentDialogueNodeId);
    const choice = node?.choices.find((candidate) => candidate.id === input.choiceId);
    if (!choice) runtimeFail("DIALOGUE_CHOICE_NOT_FOUND", `unknown choice: ${input.choiceId}`);
    if (!conditionsPass(state, choice.conditions)) runtimeFail("DIALOGUE_CHOICE_UNAVAILABLE", `choice conditions failed: ${input.choiceId}`);
    applyActions(project, state, choice.actions, context);
    if (state.status === "PLAYING" && choice.nextNodeId) state.currentDialogueNodeId = choice.nextNodeId;
    return state;
  }

  runtimeFail("INPUT_NOT_ALLOWED", `unsupported input: ${input.type}`);
}

export function snapshotRuntimeState(state) {
  return {
    status: state.status,
    currentSceneId: state.currentSceneId,
    activeDialogueSceneId: state.activeDialogueSceneId,
    currentDialogueNodeId: state.currentDialogueNodeId,
    variables: { ...state.variables },
    inventory: [...state.inventory].sort(),
    objectVisibility: { ...state.objectVisibility },
  };
}
