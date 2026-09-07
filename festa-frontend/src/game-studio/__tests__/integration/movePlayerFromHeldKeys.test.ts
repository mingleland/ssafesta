// S15P21A604-363 — 방향키 입력 처리 구조 개선.
// movePlayerFromHeldKeys는 "눌려 있는 방향키 집합"으로 한 걸음을 계산한다. 이 파일은
// (1) TOP_DOWN 대각선 합성·반대방향 상쇄, (2) PLATFORMER 우선순위(점프 > 좌우 > 아래)가
// 기존 moveReferencePlayer를 그대로 재사용해 플랫포머 로직 자체는 안 바뀌었는지,
// (3) moveReferencePlayer 리팩터링(moveTopDownByDelta 추출) 후에도 단일 방향 이동이
// 예전과 동일한지, (4) tick과 이동이 같은 시계를 쓰게 되면서 함정 "스킵"의 진짜 원인이던
// invulnerableUntilTick과 tickCount의 어긋남이 사라졌는지를 검증한다.
import { describe, expect, it } from 'vitest';
import { parseGameProject } from '../../contracts/gameProject.ts';
import {
  moveReferencePlayer,
  movePlayerFromHeldKeys,
  startReferenceRuntime,
  tickReferenceWorld,
  type MoveDirection,
} from '../../runtime/reference/referenceRuntime.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { addPlatformerScene, resizeWorldScene } from '../../studio/model/authoringCommands.ts';

const heldSet = (...directions: readonly MoveDirection[]): Set<MoveDirection> => new Set(directions);

describe('movePlayerFromHeldKeys — TOP_DOWN', () => {
  it('두 방향키가 동시에 눌리면 한 걸음으로 대각선 이동한다', () => {
    const project = createStarterProject(70);
    let runtime = startReferenceRuntime(project);
    const start = runtime.playerPosition;
    if (start === null) throw new Error('expected player position');

    runtime = movePlayerFromHeldKeys(project, runtime, heldSet('UP', 'RIGHT'));

    expect(runtime.playerPosition).toEqual({ x: start.x + 1, y: start.y - 1 });
    // 대각선일 때 좌우가 있으면 좌우를 바라보게 한다(스프라이트는 4방향뿐).
    expect(runtime.facing).toBe('RIGHT');
  });

  it('반대 방향이 함께 눌리면 상쇄되어 제자리에 머문다', () => {
    const project = createStarterProject(71);
    let runtime = startReferenceRuntime(project);
    const start = runtime.playerPosition;

    runtime = movePlayerFromHeldKeys(project, runtime, heldSet('LEFT', 'RIGHT'));
    expect(runtime.playerPosition).toEqual(start);

    runtime = movePlayerFromHeldKeys(project, runtime, heldSet('UP', 'DOWN', 'LEFT', 'RIGHT'));
    expect(runtime.playerPosition).toEqual(start);
  });

  it('빈 집합이면 아무것도 하지 않는다', () => {
    const project = createStarterProject(72);
    const runtime = startReferenceRuntime(project);
    expect(movePlayerFromHeldKeys(project, runtime, heldSet())).toBe(runtime);
  });

  it('moveReferencePlayer의 단일 방향 이동은 리팩터링 후에도 동일하다(회귀 방어)', () => {
    const project = createStarterProject(73);
    let runtime = startReferenceRuntime(project);
    const start = runtime.playerPosition;
    if (start === null) throw new Error('expected player position');

    runtime = moveReferencePlayer(project, runtime, 'RIGHT');
    expect(runtime.playerPosition).toEqual({ x: start.x + 1, y: start.y });
    expect(runtime.facing).toBe('RIGHT');
  });
});

describe('movePlayerFromHeldKeys — PLATFORMER', () => {
  const platformerProject = () => {
    const project = addPlatformerScene(createStarterProject(74));
    const platform = project.scenes.at(-1);
    if (platform?.type !== 'PLATFORMER') throw new Error('expected platformer scene');
    return { project: parseGameProject({ ...project, startSceneId: platform.id }), platform };
  };

  it('UP+RIGHT를 동시에 누르면 한 틱에 대각선으로 점프한다(S15P21A604-363 QA에서 발견된 버그의 수정)', () => {
    const { project, platform } = platformerProject();
    const spawn = platform.objects.find((object) => object.preset === 'PLAYER_SPAWN');
    if (spawn === undefined) throw new Error('expected spawn');
    const runtime = startReferenceRuntime(project);

    const moved = movePlayerFromHeldKeys(project, runtime, heldSet('UP', 'RIGHT'));

    // 좌우(x+1)와 점프(y-1)가 같은 틱에 함께 반영돼야 한다 — 예전엔 UP이 좌우를 통째로
    // 버려서 x가 그대로였다(수직 이동만 되는 "대각선 아닌 점프").
    expect(moved.playerPosition).toEqual({ x: spawn.position.x + 1, y: spawn.position.y - 1 });
    expect(moved.facing).toBe('RIGHT');
    // S15P21A604-408 — 마리오 스타일 가변 점프 도입 후, 그라운드 이탈 시점엔 고정 임펄스
    // (-3)가 아니라 "홀드 1틱째" 상태(verticalVelocity: -1, jumpHoldTicks: 1)로 시작한다.
    expect(moved.verticalVelocity).toBe(-1);
    expect(moved.jumpHoldTicks).toBe(1);
  });

  it('공중에서도 UP을 계속 누르고 있으면 홀드로 점프를 이어가면서 좌우 이동도 함께 적용된다(S15P21A604-408)', () => {
    const { project } = platformerProject();
    const runtime = startReferenceRuntime(project);
    const jumped = movePlayerFromHeldKeys(project, runtime, heldSet('UP', 'RIGHT'));
    // 그라운드에서 새로 뛰어오르는 재점프는 아니지만(이미 공중), UP을 계속 누르고 있으면
    // 홀드가 이어져서 1칸 더 상승한다(JUMP_HOLD_MAX_TICKS까지) — RIGHT도 계속 반영된다.
    const stillHolding = movePlayerFromHeldKeys(project, jumped, heldSet('UP', 'RIGHT'));
    expect(stillHolding.playerPosition).toEqual({
      x: jumped.playerPosition!.x + 1,
      y: jumped.playerPosition!.y - 1,
    });
    expect(stillHolding.jumpHoldTicks).toBe(2);
  });

  it('상승 도중 UP을 놓으면 홀드가 끝나고 다음 tick부터 중력이 이어받는다(S15P21A604-408)', () => {
    const { project } = platformerProject();
    const runtime = startReferenceRuntime(project);
    const jumped = movePlayerFromHeldKeys(project, runtime, heldSet('UP'));
    expect(jumped.jumpHoldTicks).toBe(1);

    // UP을 놓고 RIGHT만 누른 상태로 다음 held-key 평가가 들어오면 홀드가 즉시 끝난다.
    const released = movePlayerFromHeldKeys(project, jumped, heldSet('RIGHT'));
    expect(released.jumpHoldTicks).toBe(0);
    expect(released.verticalVelocity).toBe(0);
    // 놓은 그 틱 자체는 더 안 올라가고(수평만 반영), 다음 tickReferenceWorld부터 하강한다.
    expect(released.playerPosition).toEqual({ x: jumped.playerPosition!.x + 1, y: jumped.playerPosition!.y });
    const fallen = tickReferenceWorld(project, released);
    expect(fallen.playerPosition!.y).toBe(released.playerPosition!.y + 1);
  });

  it('UP을 4틱 넘게 유지해도 그 이상 높이 안 올라간다(선형비례 상한, S15P21A604-408)', () => {
    const { project } = platformerProject();
    const runtime = startReferenceRuntime(project);
    let next = movePlayerFromHeldKeys(project, runtime, heldSet('UP'));
    expect(next.jumpHoldTicks).toBe(1);
    for (let i = 0; i < 5; i += 1) {
      next = movePlayerFromHeldKeys(project, next, heldSet('UP'));
    }
    // 4틱(JUMP_HOLD_MAX_TICKS)에서 홀드가 끝나 더 이상 안 올라간다.
    expect(next.jumpHoldTicks).toBe(0);
    expect(next.playerPosition).toEqual({ x: runtime.playerPosition!.x, y: runtime.playerPosition!.y - 4 });
  });

  it('DOWN+LEFT를 동시에 누르면 급낙하와 좌우 이동이 함께 적용된다', () => {
    const { project } = platformerProject();
    const runtime = startReferenceRuntime(project);
    const moved = movePlayerFromHeldKeys(project, runtime, heldSet('DOWN', 'LEFT'));
    expect(moved.playerPosition).toEqual({ x: runtime.playerPosition!.x - 1, y: runtime.playerPosition!.y });
    expect(moved.facing).toBe('LEFT');
    expect(moved.verticalVelocity).toBeGreaterThanOrEqual(1);
  });

  it('좌우가 동시에 눌리면 상쇄되어 수평 이동하지 않는다', () => {
    const { project } = platformerProject();
    const runtime = startReferenceRuntime(project);
    const moved = movePlayerFromHeldKeys(project, runtime, heldSet('LEFT', 'RIGHT'));
    expect(moved.playerPosition).toEqual(runtime.playerPosition);
  });

  it('오른쪽만 눌리면 기존 moveReferencePlayer(RIGHT)와 동일하게 한 칸 이동한다', () => {
    const { project } = platformerProject();
    const runtime = startReferenceRuntime(project);
    const viaHeld = movePlayerFromHeldKeys(project, runtime, heldSet('RIGHT'));
    const viaDirect = moveReferencePlayer(project, runtime, 'RIGHT');
    expect(viaHeld.playerPosition).toEqual(viaDirect.playerPosition);
    expect(viaHeld.facing).toBe(viaDirect.facing);
  });
});

describe('이동과 월드 tick이 같은 시계를 쓰면서 해결되는 함정 "스킵" 증상', () => {
  // library Scene 시작 지점(8,8)에서 오른쪽으로 9칸(트랩 A, x=9)·20칸(트랩 B, x=20) 떨어진
  // 곳에 치명 함정을 두고, 방향키를 "꾹 누른" 상황을 movePlayerFromHeldKeys + tickReferenceWorld를
  // 매 틱 짝지어 호출하는 것으로 재현한다 — 이게 정확히 실제 컴포넌트의 tick 이펙트가 하는 일이다.
  //
  // 죽어서 얻는 무적은 8틱(invulnerableUntilTick = tickCount + 8)짜리라 트랩 A를 지나갈 때
  // 한 번은 통과해도(무적 프레임은 원래 그러라고 있는 것) 되지만, 트랩 B에 닿을 즈음엔 이미
  // 무적이 끝나 있어야 한다 — 이동이 tickCount와 함께 정확히 진행되기 때문이다. 예전처럼
  // tickCount가 이동 횟수와 따로 놀았다면(키 입력이 tick보다 빠르면) 무적이 실제보다 오래
  // 지속돼 트랩 B도 그냥 통과했을 것이다.
  const projectWithTwoTraps = () => {
    // 기본 library Scene은 16칸 폭이라 트랩 두 개를 충분히 떨어뜨려 놓을 공간이 없다 —
    // 30칸으로 넓혀서 트랩A(x=9)를 지나 무적이 끝난 뒤에도 트랩B(x=20)에 닿을 여유를 둔다.
    const base = resizeWorldScene(createStarterProject(75), 'library', 30, 10);
    return parseGameProject({
      ...base,
      scenes: base.scenes.map((scene) => scene.id !== 'library' || scene.type !== 'TOP_DOWN' ? scene : {
        ...scene,
        objects: [
          ...scene.objects,
          { id: 'trapA', preset: 'HAZARD' as const, position: { x: 9, y: 8 }, visible: true, components: [{ type: 'DAMAGE' as const, amount: 10 }] },
          { id: 'trapB', preset: 'HAZARD' as const, position: { x: 20, y: 8 }, visible: true, components: [{ type: 'DAMAGE' as const, amount: 10 }] },
        ],
      }),
    });
  };

  it('첫 함정의 무적 프레임은 통과시켜도, 무적이 끝난 뒤 두 번째 함정은 다시 피해를 입힌다', () => {
    const project = projectWithTwoTraps();
    let runtime = startReferenceRuntime(project);
    const spawn = runtime.playerPosition;
    if (spawn === null) throw new Error('expected player position');
    expect(runtime.maxPlayerHealth).toBeGreaterThan(0);

    const held = heldSet('RIGHT');
    // 13틱: 1틱째에 트랩A(치명)로 사망→스폰 복귀, 2~8틱은 무적 상태로 트랩A를 밟고 지나가며
    // 전진, 9틱째부터 무적이 풀리고, 13틱째에 트랩B(x=20)에 닿아 다시 사망→스폰 복귀한다.
    for (let tick = 0; tick < 13; tick += 1) {
      runtime = tickReferenceWorld(project, movePlayerFromHeldKeys(project, runtime, held));
    }

    // 트랩B에서 다시 죽었다면 체크포인트(스폰)로 돌아와 있어야 한다 — 무적이 풀리지 않았다면
    // 이 시점엔 트랩B를 그냥 지나쳐 x=20보다 더 오른쪽에 가 있었을 것이다.
    expect(runtime.playerPosition).toEqual(spawn);
    expect(runtime.playerHealth).toBe(runtime.maxPlayerHealth);
    expect(runtime.tickCount).toBe(13);
  });
});
