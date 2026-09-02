// BE 응답·오류를 shared/contracts/profile 어휘로 변환하는 유일한 지점.
import { isApiError } from '../../shared/api/client';
import type { AuthProviderId } from '../../shared/contracts/auth';
import type { ProfileNicknameErrorKind } from '../../shared/contracts/profile';

const KNOWN_PROVIDERS: readonly AuthProviderId[] = ['google', 'kakao', 'guest', 'ssafy'];

// BE 는 enum 이름 대문자(GOOGLE)로 준다. 미지 provider 는 조용히 버리지 않고 유지하면 타입이
// 깨지므로 알려진 4종만 통과 — BE 에 SSAFY 가 추가되면 소문자 변환으로 자연 수용된다.
export function toProviderIds(providers: string[]): AuthProviderId[] {
  return providers
    .map((p) => p.toLowerCase())
    .filter((p): p is AuthProviderId => (KNOWN_PROVIDERS as readonly string[]).includes(p));
}

export function toNicknameErrorKind(e: unknown): ProfileNicknameErrorKind {
  if (isApiError(e)) {
    if (e.code === 'NICKNAME_INVALID') return 'invalid';
    if (e.code === 'NICKNAME_DUPLICATED') return 'duplicated';
  }
  return 'network';
}
