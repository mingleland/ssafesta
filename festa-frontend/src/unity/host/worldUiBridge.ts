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

/**
 * 왜 나가려 하는지 — Unity 가 모르는 값이 오면 무시하고 기본 동작(최상위 모달 종료)을 한다.
 *
 *   'esc'             FE 레이어가 없는 상태에서 ESC 를 받았다 (WorldPage 중재 2단계)
 *   'overlay-closed'  Unity 초점과 짝이 된 FE 레이어가 닫혔다 (UnityHost). ESC·배경 클릭·
 *                     X 버튼·레이어 전환이 전부 여기로 모인다 — 무엇으로 닫았는지는 구분하지 않는다
 */
export type ExitWorldUiReason = 'esc' | 'overlay-closed';

/** Unity 가 쥔 최상위 모달을 닫아 달라고 요청한다. 아무것도 없으면 Unity 가 무시한다(멱등). */
export function requestExitWorldUi(instance: UnityInstance, reason: ExitWorldUiReason): void {
  instance.SendMessage(WORLD_UI_BRIDGE_OBJECT, 'RequestExitWorldUi', reason);
}

/**
 * 월드를 떠나지 않고 아바타 커스터마이징 화면을 열어 달라고 요청한다
 * (S15P21A604-820, GitLab #197 게임 파트 회신 2026-09-16).
 *
 * **`RequestExitWorldUi` 와 합치지 않는다.** 그건 "네가 연 화면을 닫아 달라" 이고 이것은
 * "아바타 화면을 열어 달라" 다 — `requestExitBooth` 를 따로 둔 것과 같은 이유다.
 *
 * Unity 는 `CharacterLobby` 를 additive 로 얹고 무대를 월드 밖으로 옮겨 그린다. World Scene·NGO
 * 연결·Player NetworkObject·좌표가 그대로 살아 있고 외형만 바뀐다 — 옛 `ReturnToCustomization`
 * 경로(연결을 끊고 로비로 나갔다 돌아오는 길)는 쓰지 않는다.
 *
 * 열리면 Unity 가 `onWorldUiState` 로 `avatar:true` 를 밀어 준다. **닫는 명령은 따로 없다** —
 * `bridge/worldUiState` 가 그것을 받아 `hasUnityModal()` 이 true 가 되고, WorldPage 의 ESC
 * 중재 2단계가 기존 `RequestExitWorldUi('esc')` 를 보낸다.
 *
 * 게스트는 커스터마이징 대상이 아니라(S15P21A604-437) Unity 가 거부하고 로그만 남긴다. 부르는
 * 화면에서 회원 여부를 먼저 보는 쪽이 맞다.
 */
export function requestAvatarCustomization(instance: UnityInstance): void {
  instance.SendMessage(WORLD_UI_BRIDGE_OBJECT, 'RequestAvatarCustomization', 'esc-menu');
}

/**
 * 부스 밖으로 내보내 달라고 요청한다 (S15P21A604-627, GitLab #174).
 *
 * **`RequestExitWorldUi` 와 합치지 않는다.** 그건 "네가 연 화면을 닫아 달라" 이고 이것은
 * "이 사람을 부스 밖으로 내보내 달라" 다. 합치면 부스 안에서 초점 카메라를 닫으려던 ESC 가
 * 사람을 밖으로 내보낼 수 있다 — 게임 파트도 같은 이유로 메서드를 갈랐다(#174 회신).
 *
 * 부스 밖에서 와도 Unity 가 무시한다(멱등) — FE 가 상태 경합을 신경 쓰지 않아도 된다.
 * 성공 콜백은 없다: 퇴장이 끝나면 `WORLD_BOOTH_CONTEXT{insideBooth:false}` 가 오고,
 * 그것이 버튼을 내린다. 퇴장 자체는 기존 F·Portal 과 같은 경로를 탄다.
 */
export function requestExitBooth(instance: UnityInstance): void {
  instance.SendMessage(WORLD_UI_BRIDGE_OBJECT, 'RequestExitBooth', 'exit-button');
}
