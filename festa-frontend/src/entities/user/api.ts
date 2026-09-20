// users/me real API — MEMBER 전용 4종. 출처: origin/develop backend user/MyAccountController.java (구현이 정본)
import { api } from '../../shared/api/client';
import type { AvatarResponse, MyAccountResponse } from './types';

export function getMe(): Promise<MyAccountResponse> {
  return api<MyAccountResponse>('/api/v1/users/me');
}

// 400 NICKNAME_INVALID / 409 NICKNAME_DUPLICATED — 응답은 갱신된 계정 전체
export function changeNickname(nickname: string): Promise<MyAccountResponse> {
  return api<MyAccountResponse>('/api/v1/users/me', {
    method: 'PATCH',
    body: JSON.stringify({ nickname }),
  });
}

// PUT — body 가 전체 값(델타 아님). 서버는 저장한 그대로 되돌려준다(#24 계약)
export function changeAvatar(avatarCode: string): Promise<AvatarResponse> {
  return api<AvatarResponse>('/api/v1/users/me/avatar', {
    method: 'PUT',
    body: JSON.stringify({ avatarCode }),
  });
}

// confirmed:false/누락 = 400 WITHDRAWAL_NOT_CONFIRMED. 성공 = 204
export function withdraw(): Promise<void> {
  return api<void>('/api/v1/users/me', {
    method: 'DELETE',
    body: JSON.stringify({ confirmed: true }),
  });
}
