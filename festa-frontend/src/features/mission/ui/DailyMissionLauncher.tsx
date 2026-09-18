// 회원이 월드 HUD에서 기존 일일 미션 패널로 바로 들어가는 진입점이다 (S15P21A604-911).
import { useSession } from '../../auth/model/session';
import { openMenuPanelScreen } from '../../world/model/worldScreen';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';

const IcMission = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M9 6h10M9 12h10M9 18h10" />
    <path d="m3.5 6 1.5 1.5L7.5 5M3.5 12 5 13.5l2.5-2.5M3.5 18 5 19.5l2.5-2.5" />
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
