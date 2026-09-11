// @vitest-environment jsdom
// 오버레이 종료 원자성 (S15P21A604-642, GitLab #132).
//
// Unity `Interact()` 는 초점 줌과 상호작용 이벤트를 같은 동기 경로에서 한다 — 여는 것은 하나의
// 동작이다. 닫는 것도 하나여야 한다: FE 레이어가 닫히면 Unity 모달도 함께 끝나야 한다.
// 남으면 `InputBridge` owner-set 에 "InteractionFocusCamera" 가 그대로 있어 `SetInputLocked('0')`
// 이 `LockedChanged` 를 발화시키지 못하고 월드 입력이 통째로 잠긴다.
//
// 여기서 잠그는 것: ① 짝이 된 레이어를 떠날 때만 명령이 간다 ② 줌 없이 열린 레이어는 보내지
// 않는다 ③ 오버레이 종류 교체는 종료가 아니다 ④ 인스턴스가 없으면 조용히 넘어간다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import type { UnityInstance, UnityProgressListener } from '../../types';
import { closeOverlay, openOverlay } from '../../../../shared/types/overlay';
import {
  __resetGameClientUiForTests,
  closeBoothManagement,
  closeGameMenu,
} from '../../../../features/world/model/gameClientUi';
import { openManagement, openMenu } from '../../../../features/world/model/worldScreen';
import { __resetWorldUiStateForTests, applyWorldUiStateJson } from '../../../bridge/worldUiState';

type Boot = { resolve: (i: UnityInstance) => void };
const boots: Boot[] = [];

vi.mock('../../sessionManager', () => {
  const start = (_c: HTMLCanvasElement, _p: UnityProgressListener) =>
    new Promise<UnityInstance>((resolve) => { boots.push({ resolve }); });
  return { acquireUnitySession: start, restartUnitySession: start, releaseUnitySession: () => {} };
});
// 이 파일의 관심사는 종료 동반 하나다 — 같은 인스턴스를 쓰는 다른 축은 호출만 지운다.
vi.mock('../../authBridge', () => ({ syncAccessToken: () => 'cleared' }));
vi.mock('../../audioBridge', () => ({ syncAudioMute: () => {} }));

const instance = (): UnityInstance => ({ SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: async () => {} });
const exitCalls = (i: UnityInstance) =>
  (i.SendMessage as ReturnType<typeof vi.fn>).mock.calls.filter((c) => c[1] === 'RequestExitWorldUi');

/** Unity 가 초점을 잡았다고 알려 온 상태 — 계약 그대로의 JSON 을 쓴다(worldUiState 계약 fixture). */
function unityFocused() {
  applyWorldUiStateJson('{"focus":true,"minigame":false}');
}

beforeEach(() => {
  boots.length = 0;
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldUiStateForTests();
});
afterEach(() => {
  cleanup();
  closeOverlay();
  __resetGameClientUiForTests();
  __resetWorldUiStateForTests();
});

async function renderBooted() {
  const { UnityHost } = await import('../../UnityHost');
  const inst = instance();
  const view = render(<UnityHost />);
  await act(async () => { boots[0].resolve(inst); });
  return { inst, view };
}

describe('오버레이 종료가 Unity 초점 종료를 동반한다 (-642)', () => {
  it('초점 + 오버레이에서 오버레이를 닫으면 종료를 요청한다', async () => {
    const { inst } = await renderBooted();
    act(() => { openOverlay('LAPTOP', { boothId: 1, objectId: 'laptop-01' }); unityFocused(); });
    act(() => { closeOverlay(); });
    expect(exitCalls(inst)).toEqual([['WorldUiBridge', 'RequestExitWorldUi', 'overlay-closed']]);
  });

  it('관리 화면도 같다 — Overlay Bus 밖이지만 초점을 쓰는 상호작용이다', async () => {
    const { inst } = await renderBooted();
    act(() => { openManagement(); unityFocused(); });
    act(() => { closeBoothManagement(); });
    expect(exitCalls(inst)).toHaveLength(1);
  });

  it('오버레이에서 Game Menu 로 넘어가도 보낸다 — 짝이 된 레이어를 떠났다', async () => {
    const { inst } = await renderBooted();
    act(() => { openOverlay('SURVEY', { kind: 'booth', boothId: 3 }); unityFocused(); });
    act(() => { openMenu(); });
    expect(exitCalls(inst)).toHaveLength(1);
  });

  it('오버레이 종류만 바뀌면 보내지 않는다 — 닫힌 것이 아니다', async () => {
    const { inst } = await renderBooted();
    act(() => { openOverlay('AI_CHAT', { boothId: 1 }); unityFocused(); });
    act(() => { openOverlay('LAPTOP', { boothId: 1, objectId: 'laptop-01' }); });
    expect(exitCalls(inst)).toHaveLength(0);
  });

  it('줌 없이 열린 레이어는 보내지 않는다 — 이벤트 NPC·상담 Quick Access 계열', async () => {
    const { inst } = await renderBooted();
    act(() => { openOverlay('EVENT_SHOP', {}); });   // Unity 초점 신호가 오지 않는다
    act(() => { closeOverlay(); });
    expect(exitCalls(inst)).toHaveLength(0);
  });

  it('Game Menu 만 열고 닫으면 Unity 에 아무것도 보내지 않는다', async () => {
    const { inst } = await renderBooted();
    act(() => { openMenu(); });
    act(() => { closeGameMenu(); });
    expect(exitCalls(inst)).toHaveLength(0);
  });

  it('부팅 직후에는 보내지 않는다 — 새 인스턴스에는 모달이 없다', async () => {
    const { inst } = await renderBooted();
    expect(exitCalls(inst)).toHaveLength(0);
  });

  it('인스턴스가 아직 없으면 조용히 넘어간다', async () => {
    const { UnityHost } = await import('../../UnityHost');
    render(<UnityHost />);   // boot 를 resolve 하지 않는다
    expect(() => {
      act(() => { openOverlay('LAPTOP', { boothId: 1, objectId: 'laptop-01' }); unityFocused(); });
      act(() => { closeOverlay(); });
    }).not.toThrow();
  });
});
