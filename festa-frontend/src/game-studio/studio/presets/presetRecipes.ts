import type {
  GameEvent,
  GameObject,
  Position2d,
} from '../../contracts/gameProject.ts';

interface RecipeBase {
  readonly objectId: string;
  readonly eventId: string;
  readonly spriteAssetId: string;
  readonly position: Position2d;
}

export interface PickupRecipe extends RecipeBase {
  readonly kind: 'PICKUP';
  readonly itemId: string;
}

export interface LockedDoorRecipe extends RecipeBase {
  readonly kind: 'LOCKED_DOOR';
  readonly requiredItemId: string;
  readonly openedVariableId: string;
  readonly destinationSceneId: string;
  readonly prompt: string;
  readonly consumeItem: boolean;
}

export type PresetRecipe = PickupRecipe | LockedDoorRecipe;

export interface MaterializedPreset {
  readonly object: GameObject;
  readonly events: readonly GameEvent[];
}

const materializePickup = (recipe: PickupRecipe): MaterializedPreset => ({
  object: {
    id: recipe.objectId,
    preset: 'ITEM',
    position: recipe.position,
    visible: true,
    components: [
      { type: 'SPRITE', assetId: recipe.spriteAssetId },
      { type: 'PICKUP', itemId: recipe.itemId },
    ],
  },
  events: [
    {
      id: recipe.eventId,
      trigger: { type: 'ON_ENTER', targetId: recipe.objectId },
      conditions: [],
      actions: [
        { type: 'GIVE_ITEM', itemId: recipe.itemId },
        { type: 'HIDE_OBJECT', objectId: recipe.objectId },
      ],
    },
  ],
});

const materializeLockedDoor = (recipe: LockedDoorRecipe): MaterializedPreset => ({
  object: {
    id: recipe.objectId,
    preset: 'DOOR',
    position: recipe.position,
    visible: true,
    components: [
      { type: 'SPRITE', assetId: recipe.spriteAssetId },
      { type: 'COLLIDER', solid: true },
      { type: 'INTERACTABLE', prompt: recipe.prompt },
    ],
  },
  events: [
    {
      id: recipe.eventId,
      trigger: { type: 'ON_INTERACT', targetId: recipe.objectId },
      conditions: [
        { type: 'HAS_ITEM', itemId: recipe.requiredItemId },
        { type: 'VARIABLE_EQUALS', variableId: recipe.openedVariableId, value: false },
      ],
      actions: [
        { type: 'SET_VARIABLE', variableId: recipe.openedVariableId, value: true },
        ...(recipe.consumeItem
          ? [{ type: 'REMOVE_ITEM' as const, itemId: recipe.requiredItemId }]
          : []),
        { type: 'HIDE_OBJECT', objectId: recipe.objectId },
        { type: 'GO_TO_SCENE', sceneId: recipe.destinationSceneId },
      ],
    },
  ],
});

export const materializePresetRecipe = (recipe: PresetRecipe): MaterializedPreset => (
  recipe.kind === 'PICKUP' ? materializePickup(recipe) : materializeLockedDoor(recipe)
);

const readSpriteAssetId = (object: GameObject): string | null => {
  const sprite = object.components.find((component) => component.type === 'SPRITE');
  return sprite?.type === 'SPRITE' ? sprite.assetId : null;
};

const readPickupRecipe = (
  object: GameObject,
  event: GameEvent,
): PickupRecipe | null => {
  if (object.preset !== 'ITEM') return null;
  const pickup = object.components.find((component) => component.type === 'PICKUP');
  const spriteAssetId = readSpriteAssetId(object);
  if (pickup?.type !== 'PICKUP' || spriteAssetId === null) return null;
  if (event.trigger.type !== 'ON_ENTER' || event.trigger.targetId !== object.id) return null;
  if (event.conditions.length !== 0 || event.actions.length !== 2) return null;
  const [give, hide] = event.actions;
  if (give?.type !== 'GIVE_ITEM' || give.itemId !== pickup.itemId) return null;
  if (hide?.type !== 'HIDE_OBJECT' || hide.objectId !== object.id) return null;
  return {
    kind: 'PICKUP',
    objectId: object.id,
    eventId: event.id,
    spriteAssetId,
    position: object.position,
    itemId: pickup.itemId,
  };
};

const readLockedDoorRecipe = (
  object: GameObject,
  event: GameEvent,
): LockedDoorRecipe | null => {
  if (object.preset !== 'DOOR') return null;
  const spriteAssetId = readSpriteAssetId(object);
  const collider = object.components.find((component) => component.type === 'COLLIDER');
  const interactable = object.components.find((component) => component.type === 'INTERACTABLE');
  if (spriteAssetId === null || collider?.type !== 'COLLIDER' || !collider.solid) return null;
  if (interactable?.type !== 'INTERACTABLE') return null;
  if (event.trigger.type !== 'ON_INTERACT' || event.trigger.targetId !== object.id) return null;
  const itemCondition = event.conditions.find((condition) => condition.type === 'HAS_ITEM');
  const variableCondition = event.conditions.find((condition) => condition.type === 'VARIABLE_EQUALS');
  if (itemCondition?.type !== 'HAS_ITEM' || variableCondition?.type !== 'VARIABLE_EQUALS') return null;
  if (variableCondition.value !== false) return null;

  const setVariable = event.actions.find((action) => action.type === 'SET_VARIABLE');
  const removeItem = event.actions.find((action) => action.type === 'REMOVE_ITEM');
  const hideObject = event.actions.find((action) => action.type === 'HIDE_OBJECT');
  const transition = event.actions.at(-1);
  if (setVariable?.type !== 'SET_VARIABLE' || setVariable.value !== true) return null;
  if (setVariable.variableId !== variableCondition.variableId) return null;
  if (removeItem?.type === 'REMOVE_ITEM' && removeItem.itemId !== itemCondition.itemId) return null;
  if (hideObject?.type !== 'HIDE_OBJECT' || hideObject.objectId !== object.id) return null;
  if (transition?.type !== 'GO_TO_SCENE') return null;

  return {
    kind: 'LOCKED_DOOR',
    objectId: object.id,
    eventId: event.id,
    spriteAssetId,
    position: object.position,
    requiredItemId: itemCondition.itemId,
    openedVariableId: variableCondition.variableId,
    destinationSceneId: transition.sceneId,
    prompt: interactable.prompt,
    consumeItem: removeItem?.type === 'REMOVE_ITEM',
  };
};

export const readPresetRecipe = (
  object: GameObject,
  event: GameEvent,
): PresetRecipe | null => (
  readPickupRecipe(object, event) ?? readLockedDoorRecipe(object, event)
);
