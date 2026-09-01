// users/me mock — real 과 같은 시그니처, BE 오류 봉투와 같은 형태로 던진다.
// 시나리오: 닉네임 '관리자' 는 정책 위반(400), 'taken' 은 중복(409) — UI 오류 상태 재현용.
import type { ApiError } from '../../shared/api/client';
import type { AvatarResponse, MyAccountResponse } from './types';

const account: MyAccountResponse = {
  userId: 1,
  nickname: '페스타참가자',
  status: 'ACTIVE',
  providers: ['GOOGLE'],
  avatarCode: null,
};

function apiError(code: string, message: string): ApiError {
  return { code, message, errors: [], warnings: [] };
}

export async function getMe(): Promise<MyAccountResponse> {
  return { ...account };
}

export async function changeNickname(nickname: string): Promise<MyAccountResponse> {
  if (nickname === '관리자') throw apiError('NICKNAME_INVALID', '사용할 수 없는 닉네임입니다.');
  if (nickname === 'taken') throw apiError('NICKNAME_DUPLICATED', '이미 사용 중인 닉네임입니다.');
  account.nickname = nickname;
  return { ...account };
}

export async function changeAvatar(avatarCode: string): Promise<AvatarResponse> {
  account.avatarCode = avatarCode;
  return { avatarCode };
}

export async function withdraw(): Promise<void> {
  // 204 등가 — 세션 정리는 features/profile 몫
}

export function __resetUserMockForTests(): void {
  account.nickname = '페스타참가자';
  account.avatarCode = null;
}
