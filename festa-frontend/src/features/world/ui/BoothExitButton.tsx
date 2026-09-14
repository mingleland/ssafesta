// 부스 나가기 버튼 (S15P21A604-627, GitLab #174).
//
// 사용자가 부스에서 나가는 법을 찾지 못했다(2026-09-10 감사 15). 부스 안에서만 보이는 클릭형
// 컨텍스트 액션을 제공한다. requestExitBooth와 Unity의 insideBooth:false 수신은 바꾸지 않는다.
//
// 상시 HUD 가 아니다. Unity 가 보낸 월드 컨텍스트가 참일 때만 뜨고 그 조건이 풀리면 사라진다
// (hud-decisions: 컨텍스트 액션). `WorldHud` 안에 있어 `hostPhase === 'ready'` 게이트(-613)도
// 그대로 받는다 — 캐릭터 선택·로딩 중에는 뜨지 않는다.
import { useWorldContext } from '../model/worldContext';
import { getReadyUnityInstance } from '../../../unity/host/sessionManager';
import { requestExitBooth } from '../../../unity/host/worldUiBridge';
import './boothExitButton.css';

export function BoothExitButton() {
  const { insideBooth } = useWorldContext();
  if (!insideBooth) return null;

  // 누른 뒤 상태를 FE 가 바꾸지 않는다. 퇴장이 끝나면 Unity 가 insideBooth:false 를 보내고
  // 그것이 이 버튼을 내린다 — 성공 콜백이 없는 계약이라 낙관적 갱신을 하면 실제로 못 나갔을 때
  // 버튼만 사라진 채 부스 안에 남는다.
  function handleExit() {
    const instance = getReadyUnityInstance();
    // mock 월드·boot 전에는 보낼 곳이 없다. 조용히 넘긴다 — WorldPage 의 ESC 중재와 같은 판단이다
    if (instance === null) return;
    requestExitBooth(instance);
  }

  return (
    <button type="button" className="booth-exit" onClick={handleExit}>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
        <path d="M16 17l5-5-5-5M21 12H9" />
      </svg>
      부스 나가기
    </button>
  );
}
