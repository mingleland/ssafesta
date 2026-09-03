// 진입 허브 — /app/home (S15P21A604-406). 그동안 `<div>home</div>` 이던 자리다.
// Persistent GameShell 이 아니다: 월드 입장과 주요 기능으로 가는 최소 진입면만 만든다.
// 통계·대시보드·활동 피드를 만들지 않는다.
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../../features/auth/model/session';
import { leaseApi } from '../../entities/booth/leaseApi.select';
import { WalletBadge } from '../../features/wallet/ui/WalletBadge';
import { PageShell } from '../../features/shell/ui/PageShell';
import './homePage.css';

const IcWorld = (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="9" />
    <path d="M3 12h18M12 3c2.5 2.7 2.5 15 0 18M12 3C9.5 5.7 9.5 18 12 21" />
  </svg>
);
const IcBooth = (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 9h16v11H4zM3 9l2-4h14l2 4" />
  </svg>
);
const IcUser = (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="8" r="4" />
    <path d="M4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5" />
  </svg>
);

export function HomePage() {
  const { kind } = useSession();
  const isMember = kind === 'member';

  const myBoothQuery = useQuery({ queryKey: ['my-booth'], queryFn: leaseApi.getMyBooth, enabled: isMember });
  const myBooth = myBoothQuery.data ?? null;
  const hasBooth = myBooth?.lease != null;

  return (
    <PageShell title="SSAFY FESTA" subtitle="축제 광장에 들어가거나 내 부스를 준비하세요" actions={<WalletBadge />}>
      <Link className="home-hero" to="/app/world">
        <span className="home-hero-icon">{IcWorld}</span>
        <span className="home-hero-body">
          <strong>축제 월드 입장</strong>
          <span className="sc-note">부스를 둘러보고 다른 참가자와 만나기</span>
        </span>
        <span className="sc-btn sc-btn-primary">입장하기</span>
      </Link>

      <div className="home-grid">
        <Link className="sc-card home-card" to="/app/booths">
          <span className="home-card-icon">{IcBooth}</span>
          <strong>부스 슬롯</strong>
          <span className="sc-note">{hasBooth ? '내 부스를 확인하고 편집합니다' : '자리를 골라 부스를 열어 보세요'}</span>
        </Link>

        {hasBooth && myBooth && (
          <Link className="sc-card home-card" to={`/app/studio/${myBooth.boothId}`}>
            <span className="home-card-icon">{IcBooth}</span>
            <strong>부스 스튜디오</strong>
            <span className="sc-note">{myBooth.name} 꾸미기</span>
          </Link>
        )}

        <Link className="sc-card home-card" to="/app/profile">
          <span className="home-card-icon">{IcUser}</span>
          <strong>내 정보</strong>
          <span className="sc-note">닉네임·보유 코인 확인</span>
        </Link>
      </div>

      {!isMember && (
        <p className="sc-note home-guest">
          게스트로 둘러보는 중입니다. 부스 임대와 편집은 <Link to="/login">소셜 로그인</Link> 후 이용할 수 있습니다.
        </p>
      )}
    </PageShell>
  );
}
