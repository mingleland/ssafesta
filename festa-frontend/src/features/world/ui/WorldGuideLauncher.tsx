// 이용 안내 진입 — 최초 1회 자동 + 이후 재열람 (S15P21A604-599).
//
// `hud-decisions` 의 "기능 launcher 금지" 에 걸리지 않는다고 본다. 금지의 대상은 월드에서 F 로 여는
// 기능(부스·설문·상담·게임)을 HUD 버튼으로도 열어 두 개의 진입을 만드는 것이고, 이것은 기능이 아니라
// **도움말**이다 — 월드에 그것을 여는 다른 길이 없다.
//
// 자동 노출 판정을 FE 가 전부 갖는다. Unity 에 "처음 들어왔는가" 를 묻지 않는다(worldGuide.ts).
// 여는 길은 Overlay Bus 하나뿐이라 배타·ESC·입력 잠금·focus 반환이 그대로 따라온다.
import { useEffect } from 'react';
import { openVisitorOverlay } from '../model/worldScreen';
import { hasSeenWorldGuide } from '../model/worldGuide';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';

const IcHelp = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="9" />
    <path d="M9.6 9.2a2.5 2.5 0 1 1 3.2 2.9c-.5.2-.8.7-.8 1.2v.4" />
    <path d="M12 17h.01" />
  </svg>
);

export function WorldGuideLauncher() {
  // 처음 들어온 사람에게만 한 번. 본 적이 있으면 아무 일도 하지 않는다 — 월드를 가리지 않는 것이
  // 기본값이고, 안내는 사용자가 부를 때 온다.
  useEffect(() => {
    if (hasSeenWorldGuide()) return;
    openVisitorOverlay('WORLD_GUIDE', {});
  }, []);

  return (
    <Tooltip content="이용 안내" placement="left">
      <button
        type="button"
        className="world-hud-help"
        aria-label="이용 안내"
        onClick={() => openVisitorOverlay('WORLD_GUIDE', {})}
      >
        {IcHelp}
      </button>
    </Tooltip>
  );
}
