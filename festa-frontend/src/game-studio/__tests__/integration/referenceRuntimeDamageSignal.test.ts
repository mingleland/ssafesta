// S15P21A604-526 — 체력이 실제로 깎일 때마다(그 순간의 피해량 + tick)를 런타임 state에
// 남겨서, UI(ReferenceGamePlayer)가 "방금 몇 데미지를 입었는지"를 감지해 캐릭터 위에
// "-N" 플로팅 표시를 띄울 수 있게 한다. damagePlayer()는 모듈 내부 함수라 직접 부르지
// 않고, 기존 통합 테스트들(referenceRuntime.test.ts/gameplaySystems.test.ts)과 같은 방식
// 으로 실제 게임플레이 동작(함정을 밟거나 적과 접촉)을 통해 간접적으로 검증한다.
import { describe, expect, it } from 'vitest';
import { parseGameProject } from '../../contracts/gameProject.ts';
import { createProjectFromTemplate } from '../../studio/model/projectTemplates.ts';
import { addTopDownScene } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { moveReferencePlayer, startReferenceRuntime, tickReferenceWorld } from '../../runtime/reference/referenceRuntime.ts';

describe('reference runtime — 피해 신호(lastDamageAmount/lastDamageTick, S15P21A604-526)', () => {
  it('시작 직후에는 아직 아무 피해도 없어 신호가 비어 있다', () => {
    const project = createStarterProject(526);
    const runtime = startReferenceRuntime(project);
    expect(runtime.lastDamageAmount).toBeNull();
  });

  it('함정(DAMAGE)에 맞으면 그 순간의 피해량과 tick이 신호에 남는다', () => {
    const extended = addTopDownScene(createStarterProject(526));
    const project = parseGameProject({
      ...extended,
      scenes: extended.scenes.map((scene) => scene.id !== 'library' || scene.type !== 'TOP_DOWN' ? scene : {
        ...scene,
        objects: [...scene.objects, {
          id: 'entryHazard',
          preset: 'HAZARD' as const,
          position: { x: 9, y: 8 },
          visible: true,
          components: [{ type: 'DAMAGE' as const, amount: 2 }],
        }],
      }),
    });
    const started = startReferenceRuntime(project);
    const hit = moveReferencePlayer(project, started, 'RIGHT');

    expect(hit.playerHealth).toBe(started.playerHealth - 2);
    expect(hit.lastDamageAmount).toBe(2);
    expect(hit.lastDamageTick).toBe(hit.tickCount);
  });

  it('무적 시간 중에 또 맞아도(피해가 실제로 적용되지 않으므로) 신호는 갱신되지 않는다', () => {
    // gameplaySystems.test.ts의 "회복 윈도우" 테스트와 동일한 설정 — 적과 접촉한 첫 틱에서
    // 피해를 입고, 무적 시간이 걸린 바로 다음 틱에서는 접촉이 계속돼도 피해가 없다.
    const original = createProjectFromTemplate(526, 'SHOOTER');
    const scene = original.scenes[0];
    if (scene?.type !== 'PLATFORMER') throw new Error('shooter scene must be platformer');
    const project = parseGameProject({
      ...original,
      scenes: [{
        ...scene,
        objects: scene.objects.map((object) => object.id === 'slime1'
          ? { ...object, components: object.components.filter((component) => component.type !== 'AUTO_MOVE') }
          : object),
      }],
    });
    const started = { ...startReferenceRuntime(project), playerPosition: { x: 5, y: 10 } };
    const damaged = tickReferenceWorld(project, started);
    // .not.toBeNull()만 쓰면 아직 구현 전이라 값이 undefined일 때도 통과해 버려서(undefined는
    // null이 아니므로) 이 테스트가 실패해야 할 때 거짓으로 통과한다 — 실제로 숫자가 들어왔는지
    // 타입까지 확인한다.
    expect(typeof damaged.lastDamageAmount).toBe('number');
    const damagedTick = damaged.lastDamageTick;

    const protectedFrame = tickReferenceWorld(project, damaged);
    expect(protectedFrame.playerHealth).toBe(damaged.playerHealth);
    // 무적 시간 중이라 새로 맞아도 damagePlayer가 아무것도 바꾸지 않으므로, 신호도
    // 직전 값 그대로 남아 있어야 한다(새 tick으로 갱신되지 않는다) — 안 그러면 실제로는
    // 피해가 없었는데도 UI가 표시를 한 번 더 띄우는 오탐이 생긴다.
    expect(protectedFrame.lastDamageTick).toBe(damagedTick);
  });

  it('체력이 0이 되는 마지막 피격(END_GAME)에도 신호가 동일하게 남는다', () => {
    const project = createProjectFromTemplate(526, 'SURVIVAL');
    const started = { ...startReferenceRuntime(project), playerPosition: { x: 7, y: 10 }, playerHealth: 1 };
    const defeated = tickReferenceWorld(project, started);
    expect(defeated.playerHealth).toBe(0);
    expect(defeated.session.status).toBe('FAILED');
    expect(defeated.lastDamageAmount).toBe(1);
    expect(defeated.lastDamageTick).toBe(defeated.tickCount);
  });

  it('체력이 0이 되는 마지막 피격(RESPAWN)에도 신호가 동일하게 남는다', () => {
    const shooterOriginal = createProjectFromTemplate(526, 'SHOOTER');
    const project = parseGameProject({ ...shooterOriginal, rules: { completion: { mode: 'ALL', objectives: [] }, playerDefeat: 'RESPAWN' } });
    const started = { ...startReferenceRuntime(project), playerPosition: { x: 5, y: 10 }, playerHealth: 1 };
    const respawned = tickReferenceWorld(project, started);

    expect(respawned.playerHealth).toBe(respawned.maxPlayerHealth);
    expect(respawned.lastDamageAmount).toBe(1);
    expect(respawned.lastDamageTick).toBe(respawned.tickCount);
  });
});
