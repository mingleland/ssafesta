// Unity WebGL 인스턴스 생성·종료를 모듈 스코프에서 단일화한다 — 컴포넌트 로컬 플래그로는
// StrictMode의 mount→cleanup→mount 사이에서 실 인스턴스가 중복 생성되는 것을 막을 수 없다.
// 출처: 013a WebGL Host 계획 B-2(single-flight)·B-3(retry 직렬화).

import { loadUnityBuild } from './loader.select';
import type { UnityInstance, UnityProgressListener } from './types';

type State =
  | { phase: 'idle' }
  | { phase: 'creating'; promise: Promise<UnityInstance> }
  | { phase: 'ready'; instance: UnityInstance }
  | { phase: 'quitting'; promise: Promise<void> };

let state: State = { phase: 'idle' };
let pendingRelease: ReturnType<typeof setTimeout> | null = null;

function cancelPendingRelease(): void {
  if (pendingRelease !== null) {
    clearTimeout(pendingRelease);
    pendingRelease = null;
  }
}

// 생성 요청 — 이미 생성 중이거나 떠 있으면 그 결과를 그대로 재사용한다(단일 비행, B-2).
// StrictMode의 두 번째 mount가 첫 mount의 cleanup 직후 동기적으로 이 함수를 다시 부르므로,
// 예약된 종료(releaseUnitySession)를 취소하고 진행 중인 생성을 이어받는다.
export function acquireUnitySession(
  canvas: HTMLCanvasElement,
  onProgress: UnityProgressListener,
): Promise<UnityInstance> {
  cancelPendingRelease();

  if (state.phase === 'creating') return state.promise;
  if (state.phase === 'ready') return Promise.resolve(state.instance);
  if (state.phase === 'quitting') {
    return state.promise.then(() => acquireUnitySession(canvas, onProgress));
  }

  const promise = loadUnityBuild(canvas, onProgress)
    .then((instance) => {
      state = { phase: 'ready', instance };
      return instance;
    })
    .catch((err: unknown) => {
      state = { phase: 'idle' };
      throw err;
    });
  state = { phase: 'creating', promise };
  return promise;
}

function quitNow(): Promise<void> {
  if (state.phase === 'ready') {
    const instance = state.instance;
    const promise = instance.Quit().then(() => {
      state = { phase: 'idle' };
    });
    state = { phase: 'quitting', promise };
    return promise;
  }
  if (state.phase === 'creating') {
    // 아직 뜨지도 않은 것을 끌 수 없다 — 생성이 끝나기를 기다렸다가 그때 종료한다.
    const promise = state.promise
      .then((instance) => instance.Quit())
      .catch(() => undefined)
      .then(() => {
        state = { phase: 'idle' };
      });
    state = { phase: 'quitting', promise };
    return promise;
  }
  if (state.phase === 'quitting') return state.promise;
  return Promise.resolve();
}

// 언마운트 시 호출 — 즉시 종료하지 않고 한 틱 미룬다. StrictMode의 mount→cleanup→mount 사이에
// acquireUnitySession이 곧바로 다시 불리면 이 예약은 취소되고 실 종료는 일어나지 않는다.
// 진짜 언마운트(라우트 이탈)라면 취소하는 쪽이 없어 예약대로 종료된다.
export function releaseUnitySession(): void {
  pendingRelease = setTimeout(() => {
    pendingRelease = null;
    void quitNow();
  }, 0);
}

// 재시도 전용 — 예약 지연 없이 기존 인스턴스 종료가 끝난 뒤에만 새로 만든다(B-3).
// onWorldGateReady는 payload가 없어 이전 시도와 새 시도를 구분할 수 없다 — 종료를 기다리지
// 않고 새로 만들면 낡은 시도의 늦은 신호가 새 시도의 것으로 착각될 수 있다.
export async function restartUnitySession(
  canvas: HTMLCanvasElement,
  onProgress: UnityProgressListener,
): Promise<UnityInstance> {
  cancelPendingRelease();
  await quitNow();
  return acquireUnitySession(canvas, onProgress);
}

// 테스트 전용 — 모듈 스코프 상태를 테스트 간에 격리한다. 프로덕션 코드에서는 호출하지 않는다.
export function __resetForTests(): void {
  cancelPendingRelease();
  state = { phase: 'idle' };
}
