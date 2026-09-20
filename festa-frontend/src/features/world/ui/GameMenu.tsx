// ESC Game Menu — 플레이어 자신과 시스템에 관한 최소 메뉴 (D-08 · user-flow-decisions §12).
//
// Navigation Hub 가 아니다. Booth Studio·Booth Management·상담 관리·Project·Survey·GAME 은
// 여기 없다 — 그 기능들의 진입은 World 안(F·NPC)이다. `계속하기` 항목도 두지 않는다:
// 메뉴를 닫는 것이 곧 World 복귀다.
//
// 데이터는 기존 모델을 그대로 쓴다 — profile.ts(닉네임·provider·avatar) + wallet 쿼리(잔액).
import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { loadProfile, useProfile } from '../../profile/model/profile';
import { useSession } from '../../auth/model/session';
import { logout } from '../../auth/model/logout';
import { walletApi } from '../../../entities/wallet/api.select';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import { openManagement, openVisitorOverlay } from '../model/worldScreen';
import { useAdminCapability } from '../../admin/model/capability';
import { getReadyUnityInstance } from '../../../unity/host/sessionManager';
import { requestAvatarCustomization } from '../../../unity/host/worldUiBridge';
import { useGameClientUi } from '../model/gameClientUi';
import type { MenuPanel } from '../model/gameClientUi';
import './gameMenu.css';

const PROVIDER_LABEL: Record<string, string> = { google: 'Google', kakao: 'Kakao', ssafy: 'SSAFY', guest: '게스트' };

const IcChevron = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M9 5l7 7-7 7" />
  </svg>
);

const IcCheck = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M5 13l4 4L19 7" />
  </svg>
);

const IcStore = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 9l1.2-4h13.6L20 9M5 9v11h14V9M10 20v-5h4v5" />
  </svg>
);

const IcUser = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" aria-hidden="true">
    <circle cx="12" cy="8" r="3.6" />
    <path d="M5 20c1.4-3.6 4.4-5.2 7-5.2s5.6 1.6 7 5.2" />
  </svg>
);

const IcHelp = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" aria-hidden="true">
    <circle cx="12" cy="12" r="8.6" />
    <path d="M9.6 9.2a2.4 2.4 0 1 1 3.3 2.2c-.7.3-.9.9-.9 1.6" />
    <path d="M12 16.4h.01" />
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
  const ui = useGameClientUi();
  const panelRef = useRef<HTMLElement>(null);
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

  // 닫힐 때 포커스를 돌려준다 — OverlayFrame(-428)과 같은 규칙. 복구하지 않으면 focus 가
  // body 에 남고, Unity 6 WebGL 은 키를 canvas 타깃으로만 받으므로(captureAllKeyboardInput=false)
  // 메뉴를 닫은 뒤 WASD·F 가 죽는다. F 모달 뒤 ESC 메뉴를 거치면 F 가 안 먹는 것으로 보인다.
  // 이 효과가 포커스를 가져가는 효과보다 먼저 등록되어야 한다 — mount 시 이전 요소를 캡처하는데,
  // 순서가 뒤바뀌면 패널 자신을 이전 요소로 잡아 닫힐 때 이미 떼어낸 노드에 focus 를 보내게 된다.
  useEffect(() => {
    const previous = document.activeElement;
    return () => {
      if (previous instanceof HTMLElement && previous.isConnected && previous !== document.body) {
        previous.focus();
        return;
      }
      document.querySelector('canvas')?.focus();
    };
  }, []);

  // 포커스는 **대화 상자 컨테이너**가 가져간다 — 하위 오버레이(설정·관리자 콘솔…)가 닫히며
  // 돌려주는 포커스(-428)가 메뉴 항목 버튼에 남으면 :focus-visible 링이 선택 흔적처럼
  // 계속 보인다(2026-09-18 지적). 컨테이너는 링을 끊어 뒀으니(menu.css) 항목에는 흔적이
  // 남지 않고, Tab 탐색은 그대로다. mount 와 하위 닫힘 둘 다 이 효과 하나가 처리한다.
  useEffect(() => {
    if (ui.menuPanel !== null) return;
    panelRef.current?.focus();
  }, [ui.menuPanel]);

  async function handleLogout() {
    await logout();
    navigate('/login', { replace: true });
  }

  // 월드를 떠나지 않고 Unity 가 아바타 화면을 연다 (S15P21A604-852, GitLab #197 게임 파트 계약).
  // 메뉴를 먼저 닫는다 — 안 닫으면 아바타 화면 위에 이 패널이 그대로 덮인다. 닫는 명령은 따로 없고,
  // Unity 가 밀어 주는 `avatar:true` 를 `hasUnityModal()` 이 받아 ESC 중재 2단계가 처리한다.
  function openAvatarCustomization() {
    const instance = getReadyUnityInstance();
    // mock 월드·boot 전에는 보낼 곳이 없다. 조용히 넘긴다 — BoothExitButton 과 같은 판단이다.
    if (instance === null) return;
    requestAvatarCustomization(instance);
    onClose();
  }

  const account = state.account;
  // 실패를 '불러오는 중...' 으로 위장하지 않는다 — 영원히 로딩처럼 보이던 자리다(T-24 정신).
  const memberNickname = state.status === 'error' ? '이름을 불러오지 못했습니다' : '불러오는 중...';
  const nickname = account?.nickname ?? (isMember ? memberNickname : '게스트');
  const provider = account?.providers[0];

  return (
    <div className="gm-root" role="presentation">
      <button type="button" className="gm-dim" aria-label="메뉴 닫기" onClick={onClose} />
      <section ref={panelRef} tabIndex={-1} className="gm-panel" role="dialog" aria-modal="true" aria-label="게임 메뉴">
        <header className="gm-head">
          <strong className="gm-title">메뉴</strong>
          <button type="button" className="gm-close" onClick={onClose} aria-label="닫기">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </header>

        {/* 프로필 요약 — 회원이면 행 전체가 내 정보 진입이다 */}
        <ProfileSummary
          nickname={nickname}
          sub={isMember ? (provider !== undefined ? PROVIDER_LABEL[provider] ?? provider : '회원') : '게스트로 둘러보는 중'}
          coin={isMember && walletQuery.data !== undefined ? walletQuery.data.balance.toLocaleString('ko-KR') + ' 코인' : null}
          coinError={isMember && walletQuery.isError}
          onOpenMyInfo={isMember ? () => onOpenPanel('myInfo') : null}
        />

        <div className="gm-body">
          <section className="gm-section">
            <h4 className="gm-section-title">바로가기</h4>
            <div className="gm-shortcut-grid">
              {/* 게스트에게는 감춘다 — 보상 수령이 `403 MEMBER_ONLY` 이고(GitLab #233), 헌법 12조상
                  게스트는 비영속이라 코인을 줄 자리가 없다. */}
              {isMember && (
                <button type="button" className="gm-card" onClick={() => onOpenPanel('missions')}>
                  {IcCheck}
                  <span>미션</span>
                </button>
              )}
              {myBoothQuery.data && (
                <button type="button" className="gm-card" onClick={openManagement}>
                  {IcStore}
                  <span>부스 관리</span>
                </button>
              )}
              {/* 게스트에게는 감춘다. Unity 가 거부하고 로그만 남기므로(S15P21A604-437) 눌리는 버튼을
                  두면 아무 일도 안 일어난 것처럼 보인다 — 게임 파트가 #197 회신에서 요청한 처리다. */}
              {isMember && (
                <button type="button" className="gm-card" onClick={openAvatarCustomization}>
                  {IcUser}
                  <span>아바타 변경</span>
                </button>
              )}
              {/* 이용 안내는 조작 안내가 아니라 안내 가이드(WORLD_GUIDE) 오버레이를 연다.
                  조작 안내는 HUD 우하단 버튼이 맡는다. visitor 층으로 열므로 메뉴는
                  clearOthers 가 걷는다 — onClose 를 따로 부르지 않는다. */}
              <button type="button" className="gm-card" onClick={() => openVisitorOverlay('WORLD_GUIDE', {})}>
                {IcHelp}
                <span>이용 안내</span>
              </button>
            </div>
          </section>

          {capability.isSuccess && capability.data.admin && (
            <section className="gm-section">
              <h4 className="gm-section-title">관리</h4>
              <button type="button" className="gm-item" onClick={() => onOpenPanel('admin')}>
                관리자 콘솔
                {IcChevron}
              </button>
            </section>
          )}

          {/* 설정에 있는 것은 음악뿐이다 — 없는 항목을 만들지 않는다(S15P21A604-618) */}
          <section className="gm-section">
            <button type="button" className="gm-item" onClick={() => onOpenPanel('settings')}>
              설정
              {IcChevron}
            </button>
            <button type="button" className="gm-item gm-item-out" onClick={() => void handleLogout()}>
              로그아웃
            </button>
          </section>
        </div>
      </section>
    </div>
  );
}
