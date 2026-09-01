// Profile 상태 기계 — UI 는 useProfile() 스냅샷과 액션만 소비한다 (S15P21A604-367).
// 데이터 출처는 entities/user(api.select — VITE_USE_MOCK 전환), 어휘는 shared/contracts/profile.
import { useSyncExternalStore } from 'react';
import { userApi } from '../../../entities/user/api.select';
import { toNicknameErrorKind, toProviderIds } from '../../../entities/user/mapper';
import { clearSession } from '../../auth/model/session';
import type { AuthProviderId } from '../../../shared/contracts/auth';
import type { ProfileNicknameErrorKind } from '../../../shared/contracts/profile';

export interface ProfileAccount {
  userId: number;
  nickname: string;
  providers: AuthProviderId[];
  avatarCode: string | null;
}

export interface ProfileState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  account: ProfileAccount | null;
  nicknameEdit: { phase: 'idle' | 'submitting' | 'success' | 'error'; errorKind?: ProfileNicknameErrorKind };
  withdrawal: { phase: 'idle' | 'confirming' | 'submitting' | 'error' };
}

const initialState: ProfileState = {
  status: 'idle',
  account: null,
  nicknameEdit: { phase: 'idle' },
  withdrawal: { phase: 'idle' },
};

let state: ProfileState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<ProfileState>): void {
  state = { ...state, ...patch };
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getProfileSnapshot(): ProfileState {
  return state;
}

export function useProfile(): ProfileState {
  return useSyncExternalStore(subscribe, getProfileSnapshot);
}

export async function loadProfile(): Promise<void> {
  if (state.status === 'loading') return;
  setState({ status: 'loading' });
  try {
    const me = await userApi.getMe();
    setState({
      status: 'ready',
      account: {
        userId: me.userId,
        nickname: me.nickname,
        providers: toProviderIds(me.providers),
        avatarCode: me.avatarCode,
      },
    });
  } catch {
    setState({ status: 'error' });
  }
}

export async function submitNickname(nickname: string): Promise<void> {
  if (state.nicknameEdit.phase === 'submitting') return;
  setState({ nicknameEdit: { phase: 'submitting' } });
  try {
    const me = await userApi.changeNickname(nickname);
    setState({
      nicknameEdit: { phase: 'success' },
      account: state.account && { ...state.account, nickname: me.nickname },
    });
  } catch (e) {
    setState({ nicknameEdit: { phase: 'error', errorKind: toNicknameErrorKind(e) } });
  }
}

export async function saveAvatar(avatarCode: string): Promise<void> {
  // 응답은 서버가 저장한 그대로 — echo 를 상태에 반영해 클라이언트 추측을 남기지 않는다(#24)
  const saved = await userApi.changeAvatar(avatarCode);
  setState({ account: state.account && { ...state.account, avatarCode: saved.avatarCode } });
}

export function beginWithdrawal(): void {
  setState({ withdrawal: { phase: 'confirming' } });
}

export function cancelWithdrawal(): void {
  setState({ withdrawal: { phase: 'idle' } });
}

// 확인(confirming)을 거친 뒤에만 호출된다 — BE 도 confirmed:true 를 요구한다(WITHDRAWAL_NOT_CONFIRMED).
// 성공하면 세션을 종료한다. session.ts 가 토큰·kind 갱신의 유일한 진입점이므로 clearSession 을 부른다.
export async function confirmWithdrawal(): Promise<void> {
  if (state.withdrawal.phase !== 'confirming') return;
  setState({ withdrawal: { phase: 'submitting' } });
  try {
    await userApi.withdraw();
    state = initialState;
    emit();
    clearSession();
  } catch {
    setState({ withdrawal: { phase: 'error' } });
  }
}

export function __resetProfileForTests(): void {
  state = initialState;
  listeners.clear();
}
