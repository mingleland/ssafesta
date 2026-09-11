// World 위 React HUD — hud-decisions.md 가 허용한 것만 그린다 (S15P21A604-406).
// 허용 4종: 이동·조작 안내 / 미니게임 score·progress(해당 콘텐츠 중에만) / Toast·Notification /
//          Consultation Quick Access(우상단, 상시 — 상담만의 예외).
// 금지: minimap · HP · quest tracker · hotbar · crosshair · mission panel · 기능 launcher.
// F 상호작용 prompt·하이라이트·이름표는 Unity 소관이라 여기서 만들지 않는다.
//
// 조작 안내 항목은 Unity 카드(S15P21A604-451)와 같은 어휘를 쓴다 — 같은 조작을 두 파트가 다른
// 말로 설명하지 않게 한다. FE 임베드에서는 Unity 카드가 숨겨져(-456) 이 목록이 유일한 안내다.
// 항목은 사용자 테스트에서 실제로 묻는 것만 고른다(GitLab #132, S15P21A604-460).
//
// **설명은 한 단어로 끝낸다** (S15P21A604-631). 이 카드는 키를 처음 익힐 때 훑는 것이지 읽는
// 문서가 아니다 — 설명이 길수록 훑기가 느려진다. 시야 조작(마우스 우클릭)은 손에 익는 것이라
// 목록에 있어도 읽히지 않아 뺐다.
//
// F 에서 "부스 입장·나가기" 를 뺀 근거: -592 가 그 말을 넣은 이유는 **나가는 방법을 안내 말고는
// 알 곳이 없었기** 때문이다. 지금은 부스 안에 있으면 나가기 버튼이 직접 뜨고(-627) 그 버튼에
// F 키 배지도 함께 있어, 화면이 스스로 말하는 것을 안내가 반복할 필요가 없다.
import { useState } from 'react';
import { BoothExitButton } from './BoothExitButton';
import { ConsultationQuickAccess } from './ConsultationQuickAccess';
import { WorldGuideLauncher } from './WorldGuideLauncher';
import './worldHud.css';

export function WorldHud() {
  const [guideOpen, setGuideOpen] = useState(true);

  return (
    <div className="world-hud">
      {/* 허용 4번 — 상담 상태 즉시 접근 */}
      <ConsultationQuickAccess />

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
          <ul className="world-hud-keys">
            <li>
              <span className="world-key">W</span>
              <span className="world-key">A</span>
              <span className="world-key">S</span>
              <span className="world-key">D</span>
              이동
            </li>
            <li>
              <span className="world-key">Shift</span>
              달리기
            </li>
            <li>
              <span className="world-key">Space</span>
              점프
            </li>
            <li>
              <span className="world-key">F</span>
              상호작용
            </li>
            <li>
              <span className="world-key">Alt</span>
              <span className="world-key">클릭</span>
              감정
            </li>
            <li>
              <span className="world-key">Esc</span>
              메뉴
            </li>
          </ul>
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
