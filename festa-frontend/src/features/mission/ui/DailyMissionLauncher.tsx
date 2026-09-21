// 회원이 월드 HUD에서 기존 일일 미션 패널로 바로 들어가는 진입점이다 (S15P21A604-911).
import { useSession } from '../../auth/model/session';
import { openMenuPanelScreen } from '../../world/model/worldScreen';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';

// 일일 미션 패널 헤더(DailyMissionOverlay)와 같은 clipboard+체크 아이콘으로 통일(2026-09-21).
// 크기는 .world-hud-control svg 가 잡으므로 viewBox 만 둔다.
const IcMission = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M9 11l3 3 8-8" />
    <path d="M20 12v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h8" />
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
