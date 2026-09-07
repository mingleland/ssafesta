// World 위 React HUD — hud-decisions.md 가 허용한 것만 그린다 (S15P21A604-406).
// 허용 4종: 이동·조작 안내 / 미니게임 score·progress(해당 콘텐츠 중에만) / Toast·Notification /
//          Consultation Quick Access(우상단, 상시 — 상담만의 예외).
// 금지: minimap · HP · quest tracker · hotbar · crosshair · mission panel · 기능 launcher.
// F 상호작용 prompt·하이라이트·이름표는 Unity 소관이라 여기서 만들지 않는다.
//
// 조작 안내 항목은 Unity 카드(S15P21A604-451)와 같은 어휘를 쓴다 — 같은 조작을 두 파트가 다른
// 말로 설명하지 않게 한다. FE 임베드에서는 Unity 카드가 숨겨져(-456) 이 목록이 유일한 안내다.
// 그래서 사용자 테스트에서 실제로 묻는 것을 기준으로 고른다(GitLab #132, S15P21A604-460):
// 이동·달리기·점프·시야·상호작용·감정 표현·창 닫기. 여기 없는 조작을 임의로 늘리지 않는다.
import { useState } from 'react';
import { ConsultationQuickAccess } from './ConsultationQuickAccess';
import './worldHud.css';

interface Props {
  /** 월드가 목업 정지 화면인지 — 안내 문구를 사실대로 바꾼다 */
  mock?: boolean;
}

export function WorldHud({ mock = false }: Props) {
  const [guideOpen, setGuideOpen] = useState(true);

  return (
    <div className="world-hud">
      {/* 허용 4번 — 상담 상태 즉시 접근 */}
      <ConsultationQuickAccess />

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
              <span className="world-key">마우스 우클릭</span>
              드래그해서 시야 돌리기
            </li>
            <li>
              <span className="world-key">F</span>
              가까운 부스·오브젝트와 상호작용
            </li>
            <li>
              <span className="world-key">Alt</span>
              <span className="world-key">클릭</span>
              감정 표현
            </li>
            <li>
              <span className="world-key">Esc</span>
              열린 창 닫기 · 메뉴 열기
            </li>
          </ul>
          {mock && <p className="world-hud-note">월드는 목업 정지 화면입니다 — 실제 이동은 Unity 연결 후 동작합니다.</p>}
        </section>
      )}

      {!guideOpen && (
        <button type="button" className="world-hud-guide-open" onClick={() => setGuideOpen(true)}>
          조작 안내
        </button>
      )}
    </div>
  );
}
