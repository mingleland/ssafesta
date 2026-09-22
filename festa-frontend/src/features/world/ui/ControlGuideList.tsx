// 조작 안내 항목 목록 — GameMenu(ESC)의 조작 안내 오버레이가 그린다.
// 그룹(이동/상호작용/월드 UI)으로 묶어 처음 보는 사람도 분류로 찾게 한다.
// 설명은 한 단어로 끝낸다 (S15P21A604-631). 우클릭 시야조작은 -631 이 "손에 익어 안 읽힌다"며
// 뺐던 항목이나, S15P21A604-798 에서 요청자 확인 하에 다시 넣는다.
import { useWorldContext } from '../model/worldContext';
import './worldHud.css';

export function ControlGuideList() {
  const { insideBooth } = useWorldContext();

  return (
    <div className="cg-root">
      <section className="cg-group">
        <h4 className="cg-title">이동</h4>
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
            <span className="world-key">우클릭</span>
            시야
          </li>
          <li>
            <span className="world-key">휠</span>
            확대·축소
          </li>
        </ul>
      </section>
      <section className="cg-group">
        <h4 className="cg-title">상호작용</h4>
        <ul className="world-hud-keys">
          <li>
            <span className="world-key">F</span>
            상호작용
          </li>
          <li>
            <span className="world-key">Alt</span>
            <span className="world-key">클릭</span>
            감정표현
          </li>
        </ul>
      </section>
      <section className="cg-group">
        <h4 className="cg-title">월드 UI</h4>
        <ul className="world-hud-keys">
          <li>
            <span className="world-key">Enter</span>
            채팅
          </li>
          {!insideBooth && (
            <li>
              <span className="world-key">Tab</span>
              미니맵
            </li>
          )}
          <li>
            <span className="world-key">Esc</span>
            메뉴
          </li>
        </ul>
      </section>
    </div>
  );
}
