// 회원이 월드 HUD에서 기존 일일 미션 패널로 바로 들어가는 진입점이다 (S15P21A604-911).
import { useSession } from '../../auth/model/session';
import { openMenuPanelScreen } from '../../world/model/worldScreen';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';

// checklist 메모장형 — 확정 시안 A(2026-09-18). 메모 프레임 안에 체크 2줄 + 밑줄.
const IcMission = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="4" y="3" width="16" height="18" rx="2" />
    <path d="M7.5 7.2l1.3 1.3 2.2-2.3" />
    <path d="M13.5 7h3" />
    <path d="M7.5 12.2l1.3 1.3 2.2-2.3" />
    <path d="M13.5 12h3" />
    <path d="M7.5 17h9" />
  </svg>
);

export function DailyMissionLauncher() {
  const { kind } = useSession();
  if (kind !== 'member') return null;

  return (
    <Tooltip content="일일 미션" placement="bottom">
      <button
        type="button"
        className="world-hud-control world-hud-missions"
        aria-label="일일 미션"
        onClick={() => openMenuPanelScreen('missions')}
      >
        {IcMission}
      </button>
    </Tooltip>
  );
}
