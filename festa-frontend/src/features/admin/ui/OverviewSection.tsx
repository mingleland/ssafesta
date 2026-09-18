// 운영 현황 — 오늘 처리할 것부터. 각 숫자는 그 섹션으로 가는 문이다.
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { adminApi } from '../../../entities/admin/api.select';
import { ErrorBanner } from './common';

function Stat({ to, value, label }: { to: string; value: string | number; label: string }) {
  return <Link to={to} className="ad-stat"><strong>{value}</strong><span>{label}</span></Link>;
}

export function OverviewSection() {
  const admins = useQuery({ queryKey: ['admin', 'admins'], queryFn: () => adminApi.listAdmins() });
  const booths = useQuery({ queryKey: ['admin', 'booths'], queryFn: () => adminApi.listBooths() });
  const pending = useQuery({ queryKey: ['admin', 'purchases', 'PENDING', 0], queryFn: () => adminApi.listPurchases('PENDING', 0, 1) });
  const purchased = useQuery({ queryKey: ['admin', 'purchases', 'PURCHASED', 0], queryFn: () => adminApi.listPurchases('PURCHASED', 0, 1) });
  const surveys = useQuery({ queryKey: ['admin', 'event-surveys'], queryFn: () => adminApi.listEventSurveys() });
  const dash = (q: { isSuccess: boolean; data?: unknown }, pick: () => string | number) => (q.isSuccess ? pick() : '…');

  return (
    <div className="ad-work">
      <section className="sc-card ad-work">
        <h3 className="sc-section-title">지금 처리할 것</h3>
        <div className="ad-stats">
          <Stat to="/app/admin/shop" value={dash(pending, () => pending.data!.totalElements)} label="경품 지급 대기" />
          <Stat to="/app/admin/shop" value={dash(purchased, () => purchased.data!.totalElements)} label="구매 완료 · 미처리" />
          <Stat to="/app/admin/booths" value={dash(booths, () => booths.data!.filter((b) => b.entryAvailable).length)} label="게시 중인 부스" />
          <Stat to="/app/admin/surveys" value={dash(surveys, () => surveys.data!.reduce((n, s) => n + s.entrantCount, 0))} label="공식 설문 참여자" />
          <Stat to="/app/admin/admins" value={dash(admins, () => admins.data!.length)} label="관리자" />
        </div>
        {pending.isError && <ErrorBanner error={pending.error} />}
        {booths.isError && <ErrorBanner error={booths.error} />}
        {surveys.isError && <ErrorBanner error={surveys.error} />}
        {admins.isError && <ErrorBanner error={admins.error} />}
      </section>
      <section className="sc-card">
        <h3 className="sc-section-title">이 콘솔이 하는 일</h3>
        <p className="sc-note">권한 판정은 매 요청 서버가 합니다 — 여기서 보이는 것은 안내이고, 실제 조치는 서버가 관리자임을 확인한 뒤에만 실행됩니다. <span className="ad-badge-fe">FE 계약</span> 표시가 붙은 자리는 BE 도달 전이라 mock 또는 오류 상태로 보입니다.</p>
      </section>
    </div>
  );
}

