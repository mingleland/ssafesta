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
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import { openManagement } from '../model/worldScreen';
import { useAdminCapability } from '../../admin/model/capability';
import type { MenuPanel } from '../model/gameClientUi';
import './gameMenu.css';

const PROVIDER_LABEL: Record<string, string> = { google: 'Google', kakao: 'Kakao', ssafy: 'SSAFY', guest: '게스트' };

const IcChevron = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M9 5l7 7-7 7" />
  </svg>
);

/** 회원이면 행 전체가 내 정보로 가는 버튼이고, 게스트면 그냥 표시다 */
function ProfileSummary({
  nickname,
  sub,
  coin,
  coinError,
  onOpenMyInfo,
}: {
  nickname: string;
  sub: string;
  coin: string | null;
  coinError: boolean;
  onOpenMyInfo: (() => void) | null;
}) {
  const inner = (
    <>
      <span className="gm-avatar" aria-hidden="true">
        {nickname.slice(0, 1)}
      </span>
      <span className="gm-who">
        <strong className="gm-nick">{nickname}</strong>
        <span className="gm-sub">{sub}</span>
        {/* 잔액 실패를 침묵하지 않는다 — 0 코인처럼 보이거나 아무것도 없는 것이 더 나쁘다 */}
        {coinError && <span className="gm-coin">잔액을 불러오지 못했습니다</span>}
        {coin !== null && <span className="gm-coin">{coin}</span>}
      </span>
    </>
  );
  if (onOpenMyInfo === null) return <div className="gm-summary">{inner}</div>;
  return (
    <button type="button" className="gm-summary gm-summary-link" onClick={onOpenMyInfo} aria-label="내 정보">
      {inner}
      {IcChevron}
    </button>
  );
}

interface Props {
  onClose: () => void;
  onOpenPanel: (panel: MenuPanel) => void;
}

export function GameMenu({ onClose, onOpenPanel }: Props) {
  const { kind } = useSession();
  const isMember = kind === 'member';
  const state = useProfile();
  const navigate = useNavigate();

  const walletQuery = useQuery({
    queryKey: ['wallet-balance'],
    queryFn: walletApi.getWallet,
    enabled: isMember, // 게스트는 403 — 요청 자체를 만들지 않는다
  });

  const myBoothQuery = useQuery({
    queryKey: ['my-booth'], // SlotListPage·useOwnerGate와 키 공유 — 캐시 재사용
    queryFn: leaseApi.getMyBooth,
    enabled: isMember,
  });

  // 관리자에게만 보이는 항목 하나. 판정은 서버가 하고(entities/admin getCapability) 회원이
  // 아니면 질의조차 하지 않는다.
  const capability = useAdminCapability();

  useEffect(() => {
    if (isMember && state.status === 'idle') void loadProfile();
  }, [isMember, state.status]);

  async function handleLogout() {
    await logout();
    navigate('/login', { replace: true });
  }

  const account = state.account;
  // 실패를 '불러오는 중...' 으로 위장하지 않는다 — 영원히 로딩처럼 보이던 자리다(T-24 정신).
  const memberNickname = state.status === 'error' ? '이름을 불러오지 못했습니다' : '불러오는 중...';
  const nickname = account?.nickname ?? (isMember ? memberNickname : '게스트');
  const provider = account?.providers[0];

  return (
    <div className="gm-root" role="presentation">
      <button type="button" className="gm-dim" aria-label="메뉴 닫기" onClick={onClose} />
      <section className="gm-panel" role="dialog" aria-modal="true" aria-label="게임 메뉴">
        {/* 상단 Profile Summary — 회원이면 행 전체가 내 정보 진입이다. 별도 pill 버튼을 옆에
            달면 닫기 버튼과 같은 모서리에서 다투고, 행을 눌러도 아무 일이 없어 어색했다. */}
        <ProfileSummary
          nickname={nickname}
          sub={isMember ? (provider !== undefined ? PROVIDER_LABEL[provider] ?? provider : '회원') : '게스트로 둘러보는 중'}
          coin={isMember && walletQuery.data !== undefined ? walletQuery.data.balance.toLocaleString('ko-KR') + ' 코인' : null}
          coinError={isMember && walletQuery.isError}
          onOpenMyInfo={isMember ? () => onOpenPanel('myInfo') : null}
        />
        <button type="button" className="gm-close" onClick={onClose} aria-label="닫기">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>

        <div className="gm-system">
          {/* 넷 다 오버레이로 연다 — 패널 안에서 접었다 폈다 하면 높이가 튀고, 항목마다 닫는
              방법이 달라진다(어떤 건 같은 버튼, 어떤 건 ESC). 자식 화면은 ESC 하나로 닫히고
              닫으면 이 메뉴로 돌아온다. */}
          <button type="button" className="gm-item" onClick={() => onOpenPanel('guide')}>
            조작 안내
            {IcChevron}
          </button>

          {myBoothQuery.data && (
            <button type="button" className="gm-item" onClick={openManagement}>
              부스 관리
              {IcChevron}
            </button>
          )}

          {capability.isSuccess && capability.data.admin && (
            <button type="button" className="gm-item" onClick={() => onOpenPanel('admin')}>
              관리자 콘솔
              {IcChevron}
            </button>
          )}

          <button type="button" className="gm-item" disabled title="준비 중입니다">
            아바타 변경
            <span className="gm-badge">준비 중</span>
          </button>

          {/* 설정에 있는 것은 음악뿐이다 — 없는 항목을 만들지 않는다(S15P21A604-618) */}
          <button type="button" className="gm-item" onClick={() => onOpenPanel('settings')}>
            설정
            {IcChevron}
          </button>

          <button type="button" className="gm-item gm-item-out" onClick={() => void handleLogout()}>
            로그아웃
          </button>
        </div>
      </section>
    </div>
  );
}
