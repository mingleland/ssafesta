// FE → Unity 명령 (S15P21A604-450, GitLab #132).
// 계약(#132 note, 2026-09-10 제안). 수신부는 Unity `WorldUiBridge` — `InputBridge`·`AuthBridge`·
// `AudioBridge`·`BoothLayoutBridge` 와 같은 자동 등록 오브젝트다(씬 무관·상태 static):
//   SendMessage('WorldUiBridge', 'RequestExitWorldUi', 'esc')
//
// **상태 통보가 아니라 요청이다.** FE 는 Unity 가 무엇을 쥐고 있는지 관측만 하고(bridge/worldUiState.ts),
// 닫는 판단은 Unity 가 한다 — 최상위 모달 하나만 종료하고 나머지는 그대로 둔다. "상태가 false 니까
// 네 기능도 닫아라" 를 쓰지 않는 이유는 2026-09-08 슬롯머신 사고다(InputBridge.cs 주석): 그때 호스트의
// 잠금 해제가 미니게임 잠금까지 풀어 돌아가던 슬롯머신이 저절로 꺼졌다.
//
// **입력 잠금과 겹치지 않는다.** `SetInputLocked` 는 그대로 두고 이 채널은 owner-set 을 읽지도 쓰지도
// 않는다. 잠금은 "월드 입력을 멈춰라", 이것은 "네가 연 화면을 닫아 달라" 로 뜻이 다르다.
import type { UnityInstance } from './types';

export const WORLD_UI_BRIDGE_OBJECT = 'WorldUiBridge';

/** 왜 나가려 하는지 — Unity 가 모르는 값이 오면 무시하고 기본 동작(최상위 모달 종료)을 한다. */
export type ExitWorldUiReason = 'esc';

/** Unity 가 쥔 최상위 모달을 닫아 달라고 요청한다. 아무것도 없으면 Unity 가 무시한다(멱등). */
export function requestExitWorldUi(instance: UnityInstance, reason: ExitWorldUiReason): void {
  instance.SendMessage(WORLD_UI_BRIDGE_OBJECT, 'RequestExitWorldUi', reason);
}
