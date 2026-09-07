// Profile 계약 정본 — Shared Contract Freeze Candidate v0.1 (2026-09-01).
// 필드는 BE MyAccountController.MyAccountResponse 실물 전사 — 임의 확장 금지.
// avatarCode null = 저장된 적 없음. 기본 아바타 표현은 UI 재량(FR-010 — 기본값 선택은 클라이언트 몫).
import type { AuthProviderId } from './auth';

export type ProfileNicknameErrorKind = 'invalid' | 'duplicated' | 'network';

export interface ProfileVM {
  status: 'idle' | 'loading' | 'ready' | 'error';
  userId: number;
  nickname: string;
  providers: AuthProviderId[];
  avatarCode: string | null;
  nicknameEdit: {
    phase: 'idle' | 'submitting' | 'success' | 'error';
    errorKind?: ProfileNicknameErrorKind;
  };
  withdrawal: {
    phase: 'idle' | 'confirming' | 'submitting' | 'error';
  };
}
