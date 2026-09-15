// 조작 안내 항목 목록 — WorldHud(상시 카드)와 GameMenu(ESC 조작안내 항목)가 같은 목록을 공유한다.
// 설명은 한 단어로 끝낸다 (S15P21A604-631). 우클릭 시야조작은 -631 이 "손에 익어 안 읽힌다"며
// 뺐던 항목이나, S15P21A604-798 에서 요청자 확인 하에 다시 넣는다.
import { useWorldContext } from '../model/worldContext';
import './worldHud.css';

export function ControlGuideList() {
  const { insideBooth } = useWorldContext();

  return (
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
        <span className="world-key">우클릭</span>
        시야
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
  );
}
