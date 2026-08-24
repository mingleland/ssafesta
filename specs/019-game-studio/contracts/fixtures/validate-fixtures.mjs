import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const fixtureDir = dirname(fileURLToPath(import.meta.url));
const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));
const clone = (value) => JSON.parse(JSON.stringify(value));

class ContractError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "ContractError";
    this.code = code;
  }
}

const fail = (code, message) => {
  throw new ContractError(code, message);
};
const expect = (condition, code, message) => {
  if (!condition) fail(code, message);
};
const uniqueMap = (values, namespace, duplicateCode) => {
  const map = new Map();
  for (const value of values) {
    expect(!map.has(value.id), duplicateCode, `duplicate ${namespace} id: ${value.id}`);
    map.set(value.id, value);
  }
  return map;
};

const terminalActions = new Set(["SHOW_DIALOGUE", "CLOSE_DIALOGUE", "GO_TO_SCENE", "COMPLETE_GAME"]);

export function validateProject(project) {
  expect(project.schemaVersion === "1.0.0", "GAME_SCHEMA_UNSUPPORTED", `unsupported schemaVersion: ${project.schemaVersion}`);

  const scenes = uniqueMap(project.scenes, "scene", "DUPLICATE_SCENE_ID");
  const variables = uniqueMap(project.variables, "variable", "DUPLICATE_VARIABLE_ID");
  const items = uniqueMap(project.items, "item", "DUPLICATE_ITEM_ID");
  const assets = uniqueMap(project.assets, "asset", "DUPLICATE_ASSET_ID");
  expect(scenes.has(project.startSceneId), "START_SCENE_NOT_FOUND", `unknown startSceneId: ${project.startSceneId}`);
  expect(
    scenes.get(project.startSceneId)?.presentation !== "OVERLAY",
    "DIALOGUE_PRESENTATION_INVALID",
    "OVERLAY dialogue cannot be the start scene",
  );

  for (const asset of project.assets) {
    expect(
      /^(builtin|asset):\/\//.test(asset.source),
      "ASSET_SOURCE_INVALID",
      `persisted asset source must use builtin:// or asset://: ${asset.id}`,
    );
  }

  for (const variable of project.variables) {
    const actualType = typeof variable.initialValue;
    const expectedType = {
      BOOLEAN: "boolean",
      INTEGER: "number",
      STRING: "string",
    }[variable.type];
    expect(actualType === expectedType, "VARIABLE_INITIAL_VALUE_INVALID", `${variable.id} initialValue type mismatch`);
    if (variable.type === "INTEGER") {
      expect(Number.isInteger(variable.initialValue), "VARIABLE_INITIAL_VALUE_INVALID", `${variable.id} initialValue must be an integer`);
    }
  }

  const allObjects = uniqueMap(
    project.scenes.flatMap((scene) => scene.objects ?? []),
    "object",
    "DUPLICATE_OBJECT_ID",
  );
  uniqueMap(
    project.scenes.flatMap((scene) => scene.events ?? []),
    "event",
    "DUPLICATE_EVENT_ID",
  );

  const validateCondition = (condition) => {
    if (condition.type === "VARIABLE_EQUALS") {
      expect(variables.has(condition.variableId), "VARIABLE_REFERENCE_NOT_FOUND", `unknown variable: ${condition.variableId}`);
    }
    if (condition.type === "HAS_ITEM") {
      expect(items.has(condition.itemId), "ITEM_REFERENCE_NOT_FOUND", `unknown item: ${condition.itemId}`);
    }
  };

  const validateAction = (action, context) => {
    if (action.type === "SET_VARIABLE") {
      expect(variables.has(action.variableId), "VARIABLE_REFERENCE_NOT_FOUND", `unknown variable: ${action.variableId}`);
    }
    if (action.type === "GIVE_ITEM" || action.type === "REMOVE_ITEM") {
      expect(items.has(action.itemId), "ITEM_REFERENCE_NOT_FOUND", `unknown item: ${action.itemId}`);
    }
    if (action.type === "SHOW_OBJECT" || action.type === "HIDE_OBJECT") {
      expect(allObjects.has(action.objectId), "OBJECT_REFERENCE_NOT_FOUND", `unknown object: ${action.objectId}`);
    }
    if (action.type === "GO_TO_SCENE") {
      expect(scenes.has(action.sceneId), "SCENE_REFERENCE_NOT_FOUND", `unknown scene: ${action.sceneId}`);
      expect(
        scenes.get(action.sceneId)?.presentation !== "OVERLAY",
        "DIALOGUE_PRESENTATION_INVALID",
        `GO_TO_SCENE cannot target OVERLAY dialogue: ${action.sceneId}`,
      );
    }
    if (action.type === "SHOW_DIALOGUE") {
      expect(scenes.get(action.sceneId)?.type === "DIALOGUE", "DIALOGUE_TARGET_INVALID", `not a DIALOGUE scene: ${action.sceneId}`);
      expect(
        scenes.get(action.sceneId)?.presentation === "OVERLAY",
        "DIALOGUE_PRESENTATION_INVALID",
        `SHOW_DIALOGUE requires OVERLAY presentation: ${action.sceneId}`,
      );
    }
    if (action.type === "CLOSE_DIALOGUE") {
      expect(
        context.dialoguePresentation === "OVERLAY",
        "DIALOGUE_CLOSE_CONTEXT_INVALID",
        `${context.ownerId} cannot close dialogue outside an OVERLAY choice`,
      );
    }
  };

  const validateActions = (actions, ownerId, context = {}) => {
    const actionContext = { ...context, ownerId };
    actions.forEach((action) => validateAction(action, actionContext));
    const terminalIndex = actions.findIndex((action) => terminalActions.has(action.type));
    expect(
      terminalIndex === -1 || terminalIndex === actions.length - 1,
      "TERMINAL_ACTION_NOT_LAST",
      `${ownerId} has actions after terminal transition`,
    );
  };

  for (const item of project.items) {
    if (item.assetId) {
      expect(assets.has(item.assetId), "ITEM_ASSET_NOT_FOUND", `unknown item asset: ${item.assetId}`);
    }
  }

  for (const scene of project.scenes) {
    if (scene.type === "TOP_DOWN" || scene.type === "PLATFORMER") {
      if (scene.type === "PLATFORMER") {
        expect(Number.isInteger(scene.gravity) && scene.gravity >= 1 && scene.gravity <= 30, "PLATFORMER_GRAVITY_INVALID", `${scene.id} gravity is invalid`);
      }
      const playerSpawns = scene.objects.filter((object) => object.preset === "PLAYER_SPAWN");
      expect(playerSpawns.length === 1, "PLAYER_SPAWN_COUNT_INVALID", `${scene.id} must have exactly one PLAYER_SPAWN`);

      if (scene.backgroundAssetId) {
        expect(assets.get(scene.backgroundAssetId)?.kind === "IMAGE", "BACKGROUND_ASSET_INVALID", `invalid background asset: ${scene.backgroundAssetId}`);
      }

      for (const layer of scene.tileLayers) {
        expect(assets.get(layer.tilesetAssetId)?.kind === "TILESET", "TILESET_ASSET_INVALID", `invalid tileset: ${layer.tilesetAssetId}`);
        expect(layer.data.length === scene.width * scene.height, "TILE_COUNT_INVALID", `${scene.id}/${layer.id} tile count mismatch`);
      }

      const localObjectIds = new Set(scene.objects.map((object) => object.id));
      for (const object of scene.objects) {
        expect(
          Number.isInteger(object.position.x)
          && Number.isInteger(object.position.y)
          && object.position.x >= 0
          && object.position.y >= 0
          && object.position.x < scene.width
          && object.position.y < scene.height,
          "OBJECT_POSITION_INVALID",
          `${object.id} position is outside ${scene.id}`,
        );
        const componentTypes = object.components.map((component) => component.type);
        expect(new Set(componentTypes).size === componentTypes.length, "DUPLICATE_COMPONENT_TYPE", `${object.id} has duplicate component types`);
        for (const component of object.components) {
          if (component.type === "SPRITE") {
            expect(assets.get(component.assetId)?.kind === "IMAGE", "SPRITE_ASSET_INVALID", `invalid sprite asset: ${component.assetId}`);
          }
          if (component.type === "PICKUP") {
            expect(items.has(component.itemId), "PICKUP_ITEM_NOT_FOUND", `unknown pickup item: ${component.itemId}`);
          }
          if (component.type === "SHOOTER") {
            expect(assets.get(component.projectileAssetId)?.kind === "IMAGE", "PROJECTILE_ASSET_INVALID", `invalid projectile asset: ${component.projectileAssetId}`);
          }
          if (component.type === "SPAWNER") {
            expect(assets.get(component.enemyAssetId)?.kind === "IMAGE", "SPAWNER_ASSET_INVALID", `invalid spawner asset: ${component.enemyAssetId}`);
          }
        }
      }

      for (const event of scene.events) {
        if (event.trigger.type === "ON_ENTER" || event.trigger.type === "ON_INTERACT") {
          expect(localObjectIds.has(event.trigger.targetId), "TRIGGER_TARGET_NOT_FOUND", `unknown trigger target: ${event.trigger.targetId}`);
        }
        event.conditions.forEach(validateCondition);
        validateActions(event.actions, event.id, { ownerType: "EVENT" });
      }
    }

    if (scene.type === "DIALOGUE") {
      expect(
        scene.presentation === "OVERLAY" || scene.presentation === "FULL_SCREEN",
        "DIALOGUE_PRESENTATION_INVALID",
        `${scene.id} has invalid presentation`,
      );
      const nodes = uniqueMap(scene.nodes, `dialogue node in ${scene.id}`, "DUPLICATE_DIALOGUE_NODE_ID");
      if (scene.backgroundAssetId) {
        expect(assets.get(scene.backgroundAssetId)?.kind === "IMAGE", "BACKGROUND_ASSET_INVALID", `invalid background asset: ${scene.backgroundAssetId}`);
      }
      expect(nodes.has(scene.startNodeId), "DIALOGUE_START_NODE_NOT_FOUND", `${scene.id} startNodeId does not exist`);
      for (const node of scene.nodes) {
        if (node.portraitAssetId) {
          expect(assets.get(node.portraitAssetId)?.kind === "IMAGE", "PORTRAIT_ASSET_INVALID", `invalid portrait asset: ${node.portraitAssetId}`);
        }
        for (const choice of node.choices) {
          if (choice.nextNodeId) {
            expect(nodes.has(choice.nextNodeId), "NEXT_DIALOGUE_NODE_NOT_FOUND", `${scene.id} unknown nextNodeId: ${choice.nextNodeId}`);
            expect(
              !choice.actions.some((action) => terminalActions.has(action.type)),
              "DIALOGUE_NEXT_WITH_TERMINAL_ACTION",
              `${choice.id} cannot combine nextNodeId with a terminal action`,
            );
          }
          (choice.conditions ?? []).forEach(validateCondition);
          validateActions(choice.actions, choice.id, {
            ownerType: "DIALOGUE_CHOICE",
            dialoguePresentation: scene.presentation,
          });
        }
      }
    }
  }
}

const pointerParts = (pointer) => {
  expect(pointer.startsWith("/"), "FIXTURE_MUTATION_INVALID", `invalid JSON pointer: ${pointer}`);
  return pointer.slice(1).split("/").map((part) => part.replaceAll("~1", "/").replaceAll("~0", "~"));
};

const readPointer = (root, pointer) => {
  let current = root;
  for (const part of pointerParts(pointer)) current = current[part];
  return current;
};

const writePointer = (root, pointer, value) => {
  const parts = pointerParts(pointer);
  const key = parts.pop();
  let parent = root;
  for (const part of parts) parent = parent[part];
  if (key === "-" && Array.isArray(parent)) parent.push(value);
  else parent[key] = value;
};

const loadNegativeFixture = (fixtureFile) => {
  const definitionPath = join(fixtureDir, fixtureFile);
  const definition = readJson(definitionPath);
  const project = clone(readJson(join(dirname(definitionPath), definition.base)));
  for (const mutation of definition.mutations) {
    if (mutation.op === "replace") writePointer(project, mutation.path, clone(mutation.value));
    else if (mutation.op === "copy") writePointer(project, mutation.path, clone(readPointer(project, mutation.from)));
    else fail("FIXTURE_MUTATION_INVALID", `unsupported mutation op: ${mutation.op}`);
  }
  return project;
};

export function runFixtureSuite() {
  const manifest = readJson(join(fixtureDir, "manifest.json"));
  let passed = 0;

  for (const fixture of manifest.positive) {
    validateProject(readJson(join(fixtureDir, fixture.file)));
    console.log(`PASS positive ${fixture.name}`);
    passed += 1;
  }

  for (const fixture of manifest.negative) {
    try {
      validateProject(loadNegativeFixture(fixture.file));
      fail("NEGATIVE_FIXTURE_ACCEPTED", `${fixture.name} unexpectedly passed`);
    } catch (error) {
      if (!(error instanceof ContractError)) throw error;
      expect(
        error.code === fixture.expectedCode,
        "NEGATIVE_FIXTURE_WRONG_ERROR",
        `${fixture.name}: expected ${fixture.expectedCode}, got ${error.code}`,
      );
      console.log(`PASS negative ${fixture.name} -> ${error.code}`);
      passed += 1;
    }
  }

  const total = manifest.positive.length + manifest.negative.length;
  console.log(`GameProject contract fixtures: ${passed}/${total} passed`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  runFixtureSuite();
}
