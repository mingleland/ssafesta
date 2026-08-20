import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const fixtureDir = dirname(fileURLToPath(import.meta.url));
const project = JSON.parse(
  readFileSync(join(fixtureDir, "minimal-top-down-dialogue.json"), "utf8"),
);

const fail = (message) => {
  throw new Error(`GameProject fixture invalid: ${message}`);
};
const expect = (condition, message) => {
  if (!condition) fail(message);
};
const uniqueMap = (values, namespace) => {
  const map = new Map();
  for (const value of values) {
    expect(!map.has(value.id), `duplicate ${namespace} id: ${value.id}`);
    map.set(value.id, value);
  }
  return map;
};

expect(project.schemaVersion === "1.0.0", "unsupported schemaVersion");

const scenes = uniqueMap(project.scenes, "scene");
const variables = uniqueMap(project.variables, "variable");
const items = uniqueMap(project.items, "item");
const assets = uniqueMap(project.assets, "asset");
expect(scenes.has(project.startSceneId), "startSceneId does not exist");

for (const variable of project.variables) {
  const actualType = typeof variable.initialValue;
  const expectedType = {
    BOOLEAN: "boolean",
    INTEGER: "number",
    STRING: "string",
  }[variable.type];
  expect(actualType === expectedType, `${variable.id} initialValue type mismatch`);
  if (variable.type === "INTEGER") {
    expect(Number.isInteger(variable.initialValue), `${variable.id} initialValue must be an integer`);
  }
}

const allObjects = uniqueMap(
  project.scenes.flatMap((scene) => scene.objects ?? []),
  "object",
);
uniqueMap(
  project.scenes.flatMap((scene) => scene.events ?? []),
  "event",
);

const validateCondition = (condition) => {
  if (condition.type === "VARIABLE_EQUALS") {
    expect(variables.has(condition.variableId), `unknown variable: ${condition.variableId}`);
  }
  if (condition.type === "HAS_ITEM") {
    expect(items.has(condition.itemId), `unknown item: ${condition.itemId}`);
  }
};

const validateAction = (action) => {
  if (action.type === "SET_VARIABLE") {
    expect(variables.has(action.variableId), `unknown variable: ${action.variableId}`);
  }
  if (action.type === "GIVE_ITEM" || action.type === "REMOVE_ITEM") {
    expect(items.has(action.itemId), `unknown item: ${action.itemId}`);
  }
  if (action.type === "SHOW_OBJECT" || action.type === "HIDE_OBJECT") {
    expect(allObjects.has(action.objectId), `unknown object: ${action.objectId}`);
  }
  if (action.type === "GO_TO_SCENE") {
    expect(scenes.has(action.sceneId), `unknown scene: ${action.sceneId}`);
  }
  if (action.type === "SHOW_DIALOGUE") {
    expect(scenes.get(action.sceneId)?.type === "DIALOGUE", `not a DIALOGUE scene: ${action.sceneId}`);
  }
};

for (const item of project.items) {
  if (item.assetId) expect(assets.has(item.assetId), `unknown item asset: ${item.assetId}`);
}

for (const scene of project.scenes) {
  if (scene.type === "TOP_DOWN") {
    const playerSpawns = scene.objects.filter((object) => object.preset === "PLAYER_SPAWN");
    expect(playerSpawns.length === 1, `${scene.id} must have exactly one PLAYER_SPAWN`);

    for (const layer of scene.tileLayers) {
      expect(assets.get(layer.tilesetAssetId)?.kind === "TILESET", `invalid tileset: ${layer.tilesetAssetId}`);
      expect(layer.data.length === scene.width * scene.height, `${scene.id}/${layer.id} tile count mismatch`);
    }

    const localObjectIds = new Set(scene.objects.map((object) => object.id));
    for (const object of scene.objects) {
      const componentTypes = object.components.map((component) => component.type);
      expect(new Set(componentTypes).size === componentTypes.length, `${object.id} has duplicate component types`);
      for (const component of object.components) {
        if (component.type === "SPRITE") {
          expect(assets.get(component.assetId)?.kind === "IMAGE", `invalid sprite asset: ${component.assetId}`);
        }
        if (component.type === "PICKUP") {
          expect(items.has(component.itemId), `unknown pickup item: ${component.itemId}`);
        }
      }
    }

    for (const event of scene.events) {
      if (event.trigger.type === "ON_ENTER" || event.trigger.type === "ON_INTERACT") {
        expect(localObjectIds.has(event.trigger.targetId), `unknown trigger target: ${event.trigger.targetId}`);
      }
      event.conditions.forEach(validateCondition);
      event.actions.forEach(validateAction);
    }
  }

  if (scene.type === "DIALOGUE") {
    const nodes = uniqueMap(scene.nodes, `dialogue node in ${scene.id}`);
    expect(nodes.has(scene.startNodeId), `${scene.id} startNodeId does not exist`);
    for (const node of scene.nodes) {
      for (const choice of node.choices) {
        if (choice.nextNodeId) {
          expect(nodes.has(choice.nextNodeId), `${scene.id} unknown nextNodeId: ${choice.nextNodeId}`);
        }
        (choice.conditions ?? []).forEach(validateCondition);
        choice.actions.forEach(validateAction);
      }
    }
  }
}

console.log("GameProject fixture semantic validation: OK");
