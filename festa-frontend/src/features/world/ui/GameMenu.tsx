// ESC Game Menu — 플레이어 자신과 시스템에 관한 최소 메뉴 (D-08 · user-flow-decisions §12).
//
// Navigation Hub 가 아니다. Booth Studio·Booth Management·상담 관리·Project·Survey·GAME 은
// 여기 없다 — 그 기능들의 진입은 World 안(F·NPC)이다. `계속하기` 항목도 두지 않는다:
// 메뉴를 닫는 것이 곧 World 복귀다.
//
// 데이터는 기존 모델을 그대로 쓴다 — profile.ts(닉네임·provider·avatar) + wallet 쿼리(잔액).
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { loadProfile, useProfile } from '../../profile/model/profile';
import { useSession } from '../../auth/model/session';
import { logout } from '../../auth/model/logout';
import { walletApi } from '../../../entities/wallet/api.select';
import './gameMenu.css';

const PROVIDER_LABEL: Record<string, string> = { google: 'Google', kakao: 'Kakao', ssafy: 'SSAFY', guest: '게스트' };

interface Props {
  onClose: () => void;
  onOpenMyInfo: () => void;
}

export function GameMenu({ onClose, onOpenMyInfo }: Props) {
  const { kind } = useSession();
  const isMember = kind === 'member';
  const state = useProfile();
  const navigate = useNavigate();

  const walletQuery = useQuery({
    queryKey: ['wallet-balance'],
    queryFn: walletApi.getWallet,
    enabled: isMember, // 게스트는 403 — 요청 자체를 만들지 않는다
  });

  useEffect(() => {
    if (isMember && state.status === 'idle') void loadProfile();
  }, [isMember, state.status]);

  async function handleLogout() {
    await logout();
    navigate('/login', { replace: true });
  }

  const account = state.account;
  const nickname = account?.nickname ?? (isMember ? '불러오는 중...' : '게스트');
  const provider = account?.providers[0];

  return (
    <div className="gm-root" role="presentation">
      <button type="button" className="gm-dim" aria-label="메뉴 닫기" onClick={onClose} />
      <section className="gm-panel" role="dialog" aria-modal="true" aria-label="게임 메뉴">
        <button type="button" className="gm-close" onClick={onClose} aria-label="닫기">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>

        {/* 상단 Profile Summary — 상위 Context 박스. 닉네임 수정 폼·탈퇴·거래내역은 My Info 소관 */}
        <div className="gm-summary">
          <span className="gm-avatar" aria-hidden="true">
            {nickname.slice(0, 1)}
          </span>
          <span className="gm-who">
            <strong className="gm-nick">{nickname}</strong>
            <span className="gm-sub">
              {isMember ? (provider !== undefined ? PROVIDER_LABEL[provider] ?? provider : '회원') : '게스트로 둘러보는 중'}
            </span>
            {isMember && walletQuery.data !== undefined && (
              <span className="gm-coin">{walletQuery.data.balance.toLocaleString('ko-KR')} 코인</span>
            )}
          </span>
          {isMember && (
            <button type="button" className="gm-myinfo" onClick={onOpenMyInfo}>
              내 정보
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M9 5l7 7-7 7" />
              </svg>
            </button>
          )}
        </div>

        {/* 의도적 여백 — 기능을 채우지 않는다(user-flow-decisions §12.2) */}
        <div className="gm-space" aria-hidden="true" />

        <div className="gm-system">
          {/* Settings 실제 기능은 Deferred(game-client-experience-draft §13) — 없는 설정을 만들지 않는다 */}
          <button type="button" className="gm-item" disabled title="준비 중입니다">
            설정
            <span className="gm-badge">준비 중</span>
          </button>
          <button type="button" className="gm-item gm-item-out" onClick={() => void handleLogout()}>
            로그아웃
          </button>
        </div>
      </section>
    </div>
  );
}
