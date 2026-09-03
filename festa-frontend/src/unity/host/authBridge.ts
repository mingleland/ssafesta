// React → Unity Access Token 주입 (spec 013a-AT, S15P21A604-91).
// 계약(#60, 2026-08-23 확정): Unity REST 는 **Access Token 원본**을 쓴다 — 전용 토큰 신설 없음.
// 수신부는 Unity `AuthBridge`(festa-unity Integration/AuthBridge.cs, -75 develop 도달):
//   SendMessage('AuthBridge', 'SetAccessToken', token) / SendMessage('AuthBridge', 'ClearAccessToken', '')
// MEMBER 만 전달하고 게스트는 미전달(열람 전용) — Unity 는 토큰 없음을 게스트로 처리한다.
// Refresh Token 은 절대 넘기지 않는다(헌법 13조). 갱신은 호스트가 하고 새 AT 를 다시 Set 한다.
import { getAccessToken } from '../../shared/api/client';
import type { SessionState } from '../../features/auth/model/session';
import type { UnityInstance } from './types';

export const AUTH_BRIDGE_OBJECT = 'AuthBridge';

export type AccessTokenSync = 'set' | 'cleared';

/**
 * 현재 세션을 Unity 에 반영한다. 멱등 — 같은 토큰을 여러 번 밀어 넣어도 Unity 는 마지막 값을 쓴다.
 * 호출 시점: 인스턴스 생성 직후·입장 게이트 ready·세션 종류/만료 변경(refresh) 시.
 */
export function syncAccessToken(instance: UnityInstance, session: Pick<SessionState, 'kind'>): AccessTokenSync {
  const token = session.kind === 'member' ? getAccessToken() : null;
  if (token !== null && token !== '') {
    instance.SendMessage(AUTH_BRIDGE_OBJECT, 'SetAccessToken', token);
    return 'set';
  }
  // 게스트·비로그인·로그아웃 — Unity 가 이전 회원 토큰을 들고 있지 않게 비운다
  instance.SendMessage(AUTH_BRIDGE_OBJECT, 'ClearAccessToken', '');
  return 'cleared';
}
