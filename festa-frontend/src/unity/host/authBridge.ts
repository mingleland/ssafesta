// React → Unity Access Token 주입 (spec 013a-AT, S15P21A604-91).
// 계약(#60, 2026-08-23 확정): Unity REST 는 **Access Token 원본**을 쓴다 — 전용 토큰 신설 없음.
// 수신부는 Unity `AuthBridge`(festa-unity Integration/AuthBridge.cs, -75 develop 도달):
//   SendMessage('AuthBridge', 'SetAccessToken', token) / SendMessage('AuthBridge', 'ClearAccessToken', '')
// 세션에 Access Token 이 있으면 종류(MEMBER·GUEST)를 가리지 않고 같은 경로로 전달한다 — BE 는 게스트 AT 로도
// POST /world-sessions 에 GUEST grant(200)를 주고, 무토큰은 401 이다(#128 §2, 2026-09-05 실측). 게스트를 빼던
// 이전 전제("열람 전용")는 월드 입장 토큰 체계(-85)보다 오래된 것이라 폐기했다. 토큰이 없을 때만 Clear.
// Refresh Token 은 절대 넘기지 않는다(헌법 13조). 갱신은 호스트가 하고 새 AT 를 다시 Set 한다.
import { getAccessToken } from '../../shared/api/client';
import type { UnityInstance } from './types';

export const AUTH_BRIDGE_OBJECT = 'AuthBridge';

export type AccessTokenSync = 'set' | 'cleared';

/**
 * 현재 세션을 Unity 에 반영한다. 멱등 — 같은 토큰을 여러 번 밀어 넣어도 Unity 는 마지막 값을 쓴다.
 * 호출 시점: 인스턴스 생성 직후·입장 게이트 ready·세션 종류/만료 변경(refresh) 시.
 */
export function syncAccessToken(instance: UnityInstance): AccessTokenSync {
  // 토큰과 kind 는 session store 가 항상 함께 갱신한다(T005) — kind 분기 없이 토큰 유무만 본다.
  const token = getAccessToken();
  if (token !== null && token !== '') {
    instance.SendMessage(AUTH_BRIDGE_OBJECT, 'SetAccessToken', token);
    return 'set';
  }
  // 비로그인·로그아웃 — Unity 가 이전 토큰을 들고 있지 않게 비운다
  instance.SendMessage(AUTH_BRIDGE_OBJECT, 'ClearAccessToken', '');
  return 'cleared';
}
