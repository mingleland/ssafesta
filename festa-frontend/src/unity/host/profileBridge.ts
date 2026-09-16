// React 프로필 변경을 이미 실행 중인 Unity 월드의 표시 이름 동기화로 잇는다.
// 월드가 아직 부팅 중이면 마지막 확정 이름을 보관했다가 UnityHost가 준비된 인스턴스에 전달한다.
import { getReadyUnityInstance } from './sessionManager';
import type { UnityInstance } from './types';

export const PROFILE_BRIDGE_OBJECT = 'ProfileNicknameBridge';

let latestNickname: string | null = null;

function send(instance: UnityInstance, nickname: string): void {
  instance.SendMessage(PROFILE_BRIDGE_OBJECT, 'SetNickname', nickname);
}

/** 서버가 확정한 닉네임만 Unity로 보낸다. 실패 응답은 이 경로에 들어오지 않는다. */
export function syncNicknameToUnity(nickname: string): void {
  latestNickname = nickname;
  const instance = getReadyUnityInstance();
  if (instance !== null) send(instance, nickname);
}

/** 새 Unity 인스턴스가 준비됐을 때, 부팅 사이에 확정된 이름을 잃지 않게 다시 보낸다. */
export function syncPendingNickname(instance: UnityInstance): void {
  if (latestNickname !== null) send(instance, latestNickname);
}

/** 테스트 전용 */
export function __resetProfileBridgeForTests(): void {
  latestNickname = null;
}
