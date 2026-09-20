// 관리자 콘솔 본문 — 좌측 섹션 내비 + 우측 작업 영역 (S15P21A604-828).
//
// 월드 위 오버레이 안에서 산다. 라우터를 쓰지 않는 이유는 오버레이가 주소를 바꾸지 않기 때문이다 —
// 섹션 이동은 `model/consoleState` 가 들고, 내비는 링크가 아니라 버튼이다.
import { ADMIN_SECTIONS } from '../model/sections';
import {
  openAdminSection,
  selectAdminUser,
  selectEventResponse,
  selectEventSurvey,
  useAdminConsole,
} from '../model/consoleState';
import { AdminsSection } from './AdminsSection';
import { BoothsSection } from './BoothsSection';
import { EventShopSection } from './EventShopSection';
import { EventSurveySection } from './EventSurveySection';
import { MembersSection } from './MembersSection';
import { OverviewSection } from './OverviewSection';
import { WalletsSection } from './WalletsSection';
import './admin.css';

export function AdminConsole() {
  const { section, userId, surveyKey, responseId } = useAdminConsole();
  const current = ADMIN_SECTIONS.find((s) => s.id === section) ?? ADMIN_SECTIONS[0];

  return (
    <div className="ad-shell">
      <nav className="ad-nav" aria-label="콘솔 섹션">
        {ADMIN_SECTIONS.map((s) => (
          <button
            key={s.id}
            type="button"
            className="ad-nav-item"
            aria-current={s.id === section ? 'page' : undefined}
            onClick={() => openAdminSection(s.id)}
          >
            {s.label}
            <small>{s.hint}</small>
          </button>
        ))}
      </nav>
      <div className="ad-work ad-workspace">
        <div className="ad-head"><h2>{current.label}</h2><p>{current.hint}</p></div>
        {section === 'overview' && <OverviewSection />}
        {section === 'members' && <MembersSection userId={userId} onSelect={selectAdminUser} />}
        {section === 'admins' && <AdminsSection />}
        {section === 'wallets' && <WalletsSection userId={userId} onSelect={selectAdminUser} />}
        {section === 'shop' && <EventShopSection />}
        {section === 'surveys' && (
          <EventSurveySection
            surveyKey={surveyKey}
            responseId={responseId}
            onSelectSurvey={selectEventSurvey}
            onSelectResponse={selectEventResponse}
          />
        )}
        {section === 'booths' && <BoothsSection />}
      </div>
    </div>
  );
}
