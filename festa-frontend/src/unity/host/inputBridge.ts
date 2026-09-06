// React → Unity 월드 입력 잠금 (G-8, S15P21A604-450, GitLab #132).
// 계약(#132, 2026-09-05 게임 파트 확정 · S15P21A604-434 !279 develop 도달). 수신부는 Unity `InputBridge`
// (festa-unity Integration/InputBridge.cs — AuthBridge 와 같은 자동 등록 오브젝트, 씬 무관·상태 static):
//   SendMessage('InputBridge', 'SetInputLocked', '1')   // Overlay 열림 → 월드 입력 정지
//   SendMessage('InputBridge', 'SetInputLocked', '0')   // Overlay 닫힘 → 재개
// 잠김 중 Unity 는 WASD·Shift·Space(PlayerMovement), F(BoothInteractionInput), Alt+클릭 이모트를 읽지 않는다.
//
// 왜 FE 가 이걸 보내야 하는가: !279 로 WebGLInput.captureAllKeyboardInput=false 가 되면서 canvas 에 focus 가
// 있을 때만 Unity 가 키를 받는다. 그런데 오버레이가 열려도 focus 가 canvas 에 남아 있을 수 있어(모달 밖 클릭 등)
// focus 만으로는 경계가 완결되지 않는다 — 잠금은 명시적으로 보낸다.
//
// 멱등이라 중복 호출·순서 어긋남에 안전하다. 재접속·씬 전환으로 상태가 흔들릴 때 다시 밀어 넣어도 된다.
import type { UnityInstance } from './types';

export const INPUT_BRIDGE_OBJECT = 'InputBridge';

/** Overlay 개폐를 Unity 입력 잠금에 반영한다. locked=true 면 월드 입력 정지. */
export function syncInputLock(instance: UnityInstance, locked: boolean): void {
  instance.SendMessage(INPUT_BRIDGE_OBJECT, 'SetInputLocked', locked ? '1' : '0');
}
