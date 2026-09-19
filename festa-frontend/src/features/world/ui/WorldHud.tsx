// World 위 React HUD — hud-decisions.md 가 허용한 것만 그린다 (S15P21A604-406).
// 허용 6종: 이동·조작 안내 / 미니게임 score·progress(해당 콘텐츠 중에만) / Toast·Notification /
//          Consultation Quick Access / 월드 채팅 / 전체화면.
// 금지: minimap · HP · quest tracker · hotbar · crosshair · mission panel · 기능 launcher.
// F 상호작용 prompt·하이라이트·이름표는 Unity 소관이라 여기서 만들지 않는다.
//
// **조작 안내는 여기서 걷었다** (2026-09-16). ESC 메뉴의 조작 안내 오버레이가 같은 목록
// (`ControlGuideList`)을 그리므로 상시 카드로 화면 한 귀퉁이를 계속 차지할 이유가 없다.
import { BoothExitButton } from './BoothExitButton';
import { ConsultationQuickAccess } from './ConsultationQuickAccess';
import { DailyMissionLauncher } from '../../mission/ui/DailyMissionLauncher';
import { openMenuPanelScreen } from '../model/worldScreen';
import { toggleFullscreen, useFullscreen } from '../../../shared/ui/fullscreen';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';
import './worldHud.css';

export function WorldHud() {
  // HUD 의 버튼은 마우스로 눌러도 focus 를 가져가지 않는다 (S15P21A604-648). 브라우저 기본은 mousedown
  // 에서 그 버튼으로 focus 를 옮기는데, 그러면 canvas 가 focus 를 잃어 WASD·F 가 Unity 에 안 들어간다
  // (!279 captureAllKeyboardInput=false). 나가기 버튼은 눌린 뒤 사라져 focus 가 body 로 떨어지고,
  // 이용 안내 버튼은 -428 이 "연 요소" 인 그 버튼으로 focus 를 돌려준다 — 둘 다 캔버스를 다시 클릭하기
  // 전까지 키가 죽는다(2026-09-11 5173 실측). mousedown 의 기본 동작만 막는다: click 은 mouseup 뒤에
  // 그대로 오고, Tab 으로 HUD 버튼에 가는 키보드 경로도 그대로다. HUD 안에는 입력창이 없다.
  return (
    <div className="world-hud" onMouseDown={(event) => event.preventDefault()}>
      {/* 좌상단 — 일일 미션 (S15P21A604-911) */}
      <DailyMissionLauncher />

      {/* 우상단 한 줄 — 상담 · 전체화면 (2026-09-16). 세로로 쌓던 것을 가로로 폈다.
          순서는 DOM 그대로다: .cqa 가 row-reverse 라 상담이 오른쪽 끝에 서고 전체화면이 그 왼쪽에 붙는다. */}
      <ConsultationQuickAccess />
      <FullscreenToggle />

      {/* 조작 안내 — 우하단. 여는 것은 ESC 메뉴의 그 오버레이와 같은 'guide' 패널이다.
          HUD 에서 직접 열 뿐이고, 새 슬롯을 만들지 않으므로 배타·ESC·입력 잠금이 그대로
          따라온다. 닫으면 월드로 돌아간다(메뉴를 거치지 않는다). */}
      <GuideButton />

      {/* 컨텍스트 액션 — 부스 안일 때만 뜬다. 상시 HUD 가 아니다 (S15P21A604-627, #174) */}
      <BoothExitButton />
    </div>
  );
}

function GuideButton() {
  return (
    <Tooltip content="조작 안내" placement="left">
      <button
        type="button"
        className="world-hud-fullscreen world-hud-guide"
        onClick={() => openMenuPanelScreen('guide')}
        aria-label="조작 안내"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <rect x="2.5" y="6" width="19" height="12" rx="2.5" />
          <path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M6 14h.01M18 14h.01M9.5 14h5" />
        </svg>
      </button>
    </Tooltip>
  );
}

// 상태의 정본은 브라우저다 — F11 이나 ESC 로 빠져나가도 `fullscreenchange` 로 아이콘이 따라간다.
// FE 가 자기 state 로 들고 있으면 그 두 경로에서 곧바로 어긋난다.
function FullscreenToggle() {
  const full = useFullscreen();
  const label = full ? '전체화면 끄기' : '전체화면';
  return (
    <Tooltip content={label} placement="bottom">
      <button
        type="button"
        className="world-hud-control world-hud-fullscreen"
        onClick={() => { void toggleFullscreen(); }}
        aria-pressed={full}
        aria-label={label}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          {full ? (
            <path d="M3 8h3a2 2 0 0 0 2-2V3M21 8h-3a2 2 0 0 1-2-2V3M3 16h3a2 2 0 0 1 2 2v3M21 16h-3a2 2 0 0 0-2 2v3" />
          ) : (
            <path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3" />
          )}
        </svg>
      </button>
    </Tooltip>
  );
}
