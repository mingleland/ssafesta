import { describe, expect, it } from 'vitest';
import {
  materializePresetRecipe,
  readPresetRecipe,
  type LockedDoorRecipe,
  type PickupRecipe,
} from '../../studio/presets/presetRecipes.ts';

describe('preset recipes', () => {
  it('round-trips pickup convenience fields through canonical components and events', () => {
    const recipe: PickupRecipe = {
      kind: 'PICKUP',
      objectId: 'roomKey',
      eventId: 'takeKey',
      spriteAssetId: 'keyImage',
      itemId: 'key',
      position: { x: 2, y: 1 },
    };

    const materialized = materializePresetRecipe(recipe);

    expect(materialized.object.components).toEqual([
      { type: 'SPRITE', assetId: 'keyImage' },
      { type: 'PICKUP', itemId: 'key' },
    ]);
    expect(materialized.events[0]?.actions.map((action) => action.type))
      .toEqual(['GIVE_ITEM', 'HIDE_OBJECT']);
    expect(readPresetRecipe(materialized.object, materialized.events[0]!)).toEqual(recipe);
  });

  it.each([true, false])('round-trips a locked door with consumeItem=%s', (consumeItem) => {
    const recipe: LockedDoorRecipe = {
      kind: 'LOCKED_DOOR',
      objectId: 'exitDoor',
      eventId: 'openDoor',
      spriteAssetId: 'doorImage',
      requiredItemId: 'key',
      openedVariableId: 'doorOpened',
      destinationSceneId: 'ending',
      prompt: '문 열기',
      consumeItem,
      position: { x: 3, y: 2 },
    };

    const materialized = materializePresetRecipe(recipe);
    const actions = materialized.events[0]?.actions.map((action) => action.type);

    expect(actions).toEqual(consumeItem
      ? ['SET_VARIABLE', 'REMOVE_ITEM', 'HIDE_OBJECT', 'GO_TO_SCENE']
      : ['SET_VARIABLE', 'HIDE_OBJECT', 'GO_TO_SCENE']);
    expect(readPresetRecipe(materialized.object, materialized.events[0]!)).toEqual(recipe);
  });

  it('does not reinterpret a hand-edited event that no longer matches a recipe', () => {
    const recipe: PickupRecipe = {
      kind: 'PICKUP',
      objectId: 'roomKey',
      eventId: 'takeKey',
      spriteAssetId: 'keyImage',
      itemId: 'key',
      position: { x: 2, y: 1 },
    };
    const materialized = materializePresetRecipe(recipe);
    const event = materialized.events[0];
    if (event === undefined) throw new Error('materialized event missing');

    expect(readPresetRecipe(materialized.object, {
      ...event,
      trigger: { type: 'ON_INTERACT', targetId: 'roomKey' },
    })).toBeNull();
  });
});
