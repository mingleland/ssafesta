// World 위 React HUD — hud-decisions.md 가 허용한 것만 그린다 (S15P21A604-406).
// 허용 6종: 이동·조작 안내 / 미니게임 score·progress(해당 콘텐츠 중에만) / Toast·Notification /
//          Consultation Quick Access / 월드 채팅 / 전체화면.
// 금지: minimap · HP · quest tracker · hotbar · crosshair · mission panel · 기능 launcher.
// F 상호작용 prompt·하이라이트·이름표는 Unity 소관이라 여기서 만들지 않는다.
//
// 조작 안내 항목은 Unity 카드(S15P21A604-451)와 같은 어휘를 쓴다 — 같은 조작을 두 파트가 다른
// 말로 설명하지 않게 한다. FE 임베드에서는 Unity 카드가 숨겨져(-456) 이 목록이 유일한 안내다.
// 항목은 사용자 테스트에서 실제로 묻는 것만 고른다(GitLab #132, S15P21A604-460).
//
// **설명은 한 단어로 끝낸다** (S15P21A604-631). 이 카드는 키를 처음 익힐 때 훑는 것이지 읽는
// 문서가 아니다 — 설명이 길수록 훑기가 느려진다. 시야 조작(마우스 우클릭)은 -631 이 "손에 익어
// 안 읽힌다"며 뺐던 항목이나, S15P21A604-798 에서 요청자 확인 하에 결정을 번복해 다시 넣었다.
//
// F 에서 "부스 입장·나가기" 를 뺀 근거: 부스 안에서는 클릭형 나가기 버튼이 직접 떠서 안내가
// 같은 동작을 반복할 필요가 없다(S15P21A604-740).
import { useState } from 'react';
import { BoothExitButton } from './BoothExitButton';
import { ConsultationQuickAccess } from './ConsultationQuickAccess';
import { ControlGuideList } from './ControlGuideList';
import { WorldGuideLauncher } from './WorldGuideLauncher';
import { toggleFullscreen, useFullscreen } from '../../../shared/ui/fullscreen';
import './worldHud.css';

export function WorldHud() {
  const [guideOpen, setGuideOpen] = useState(true);

  // HUD 의 버튼은 마우스로 눌러도 focus 를 가져가지 않는다 (S15P21A604-648). 브라우저 기본은 mousedown
  // 에서 그 버튼으로 focus 를 옮기는데, 그러면 canvas 가 focus 를 잃어 WASD·F 가 Unity 에 안 들어간다
  // (!279 captureAllKeyboardInput=false). 나가기 버튼은 눌린 뒤 사라져 focus 가 body 로 떨어지고,
  // 이용 안내 버튼은 -428 이 "연 요소" 인 그 버튼으로 focus 를 돌려준다 — 둘 다 캔버스를 다시 클릭하기
  // 전까지 키가 죽는다(2026-09-11 5173 실측). mousedown 의 기본 동작만 막는다: click 은 mouseup 뒤에
  // 그대로 오고, Tab 으로 HUD 버튼에 가는 키보드 경로도 그대로다. HUD 안에는 입력창이 없다.
  return (
    <div className="world-hud" onMouseDown={(event) => event.preventDefault()}>
      {/* 허용 4번 — 상담 상태 즉시 접근 */}
      <ConsultationQuickAccess />

      {/* 허용 6번 — 브라우저 전체화면 토글 (S15P21A604-733, hud-decisions.md) */}
      <FullscreenToggle />

      {/* 컨텍스트 액션 — 부스 안일 때만 뜬다. 상시 HUD 가 아니다 (S15P21A604-627, #174) */}
      <BoothExitButton />

      {guideOpen && (
        <section className="world-hud-guide" aria-label="조작 안내">
          <header className="world-hud-guide-head">
            <span>조작 안내</span>
            <button type="button" onClick={() => setGuideOpen(false)} aria-label="조작 안내 닫기">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden="true">
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            </button>
          </header>
          <ControlGuideList />
        </section>
      )}

      {!guideOpen && (
        <button type="button" className="world-hud-guide-open" onClick={() => setGuideOpen(true)}>
          조작 안내
        </button>
      )}

      {/* 이용 안내(무엇을 할 수 있는가) — 위 조작 안내(어떻게 움직이는가)와 다른 축이다 (-599) */}
      <WorldGuideLauncher />
    </div>
  );
}

// 상태의 정본은 브라우저다 — F11 이나 ESC 로 빠져나가도 `fullscreenchange` 로 아이콘이 따라간다.
// FE 가 자기 state 로 들고 있으면 그 두 경로에서 곧바로 어긋난다.
function FullscreenToggle() {
  const full = useFullscreen();
  const label = full ? '전체화면 끄기' : '전체화면';
  return (
    <button
      type="button"
      className="world-hud-fullscreen"
      onClick={() => { void toggleFullscreen(); }}
      aria-pressed={full}
      aria-label={label}
      title={label}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        {full ? (
          <path d="M3 8h3a2 2 0 0 0 2-2V3M21 8h-3a2 2 0 0 1-2-2V3M3 16h3a2 2 0 0 1 2 2v3M21 16h-3a2 2 0 0 0-2 2v3" />
        ) : (
          <path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3" />
        )}
      </svg>
    </button>
  );
}
