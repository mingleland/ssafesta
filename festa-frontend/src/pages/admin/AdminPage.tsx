// 관리자 콘솔 — /app/admin/:section (S15P21A604-828). 좌측 내비 + 우측 작업 영역.
// 선택 상태(userId·surveyKey·responseId)는 URL 검색 파라미터에 둔다 — 새로고침·뒤로가기·링크 공유가 그대로 된다.
import { Navigate, NavLink, useParams, useSearchParams } from 'react-router-dom';
import { PageShell } from '../../features/shell/ui/PageShell';
import { ADMIN_SECTIONS, isAdminSection } from '../../features/admin/model/sections';
import { AdminsSection } from '../../features/admin/ui/AdminsSection';
import { BoothsSection } from '../../features/admin/ui/BoothsSection';
import { EventShopSection } from '../../features/admin/ui/EventShopSection';
import { EventSurveySection } from '../../features/admin/ui/EventSurveySection';
import { MembersSection } from '../../features/admin/ui/MembersSection';
import { OverviewSection } from '../../features/admin/ui/OverviewSection';
import { WalletsSection } from '../../features/admin/ui/WalletsSection';
import '../../features/admin/ui/admin.css';

export function AdminPage() {
  const { section } = useParams();
  const [params, setParams] = useSearchParams();
  if (!isAdminSection(section)) return <Navigate to="/app/admin/overview" replace />;

  const userId = params.get('userId');
  const selectedUserId = userId !== null && /^\d+$/.test(userId) ? Number(userId) : null;
  const setUserId = (next: number | null) => {
    const p = new URLSearchParams(params);
    if (next === null) p.delete('userId'); else p.set('userId', String(next));
    setParams(p, { replace: true });
  };
  const setParam = (key: string) => (next: string | number | null) => {
    const p = new URLSearchParams(params);
    if (next === null) p.delete(key); else p.set(key, String(next));
    setParams(p, { replace: true });
  };
  const responseId = params.get('responseId');
  const current = ADMIN_SECTIONS.find((s) => s.id === section)!;

  return (
    <PageShell title="관리자 콘솔" subtitle="회원 · 권한 · 코인 · 경품 · 공식 설문 · 부스" backTo="/app/world">
      <div className="ad-shell">
        <nav className="ad-nav" aria-label="콘솔 섹션">
          {ADMIN_SECTIONS.map((s) => (
            <NavLink key={s.id} to={`/app/admin/${s.id}`} end>
              {s.label}
              <small>{s.hint}</small>
            </NavLink>
          ))}
        </nav>
        <div className="ad-work">
          <div className="ad-head"><h2>{current.label}</h2><p>{current.hint}</p></div>
          {section === 'overview' && <OverviewSection />}
          {section === 'members' && <MembersSection userId={selectedUserId} onSelect={setUserId} />}
          {section === 'admins' && <AdminsSection />}
          {section === 'wallets' && <WalletsSection userId={selectedUserId} onSelect={setUserId} />}
          {section === 'shop' && <EventShopSection />}
          {section === 'surveys' && (
            <EventSurveySection
              surveyKey={params.get('surveyKey')}
              responseId={responseId !== null && /^\d+$/.test(responseId) ? Number(responseId) : null}
              onSelectSurvey={setParam('surveyKey')}
              onSelectResponse={setParam('responseId')}
            />
          )}
          {section === 'booths' && <BoothsSection />}
        </div>
      </div>
    </PageShell>
  );
}

