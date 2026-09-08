// AI 직원 대화 테스트 진입점 — DEV ONLY (프로덕션 라우트에서 제외, app/router/index.tsx 참고).
// World·Unity mock 화면을 거치지 않고 boothId·agentId를 바로 바꿔가며 AI_CHAT 오버레이를 열어
// 실 RAG 서버와 왕복 테스트하려는 도구다. 제품 화면이 아니다 — MockInteractionBar(DEV_ONLY, 08조와
// 동일 계열)와 달리 World 진입 자체가 필요 없어 반복 실행이 빠르다.
// 오버레이가 회원/게스트로 갈리는 로직은 여기서 만들지 않는다 — AiChatOverlay가 이미
// useSession().kind로 그 둘을 가른다(S15P21A604-118). 이 페이지는 그 갈림을 확인할 boothId·
// agentId 입력과 실행 버튼, 그리고 오버레이를 실제로 그릴 OverlayHost만 놓는다.
import { useState } from 'react';
import { openOverlay } from '../../shared/types/overlay';
import { OverlayHost } from '../../features/overlay/OverlayHost';
import { useSession } from '../../features/auth/model/session';
import './devAiChatPage.css';

export function DevAiChatPage() {
  const { kind } = useSession();
  const [boothId, setBoothId] = useState(1);
  const [agentId, setAgentId] = useState(1);

  return (
    <div className="dev-ai-page">
      <span className="dev-ai-badge">DEV ONLY · 제품 화면 아님</span>

      <div className="dev-ai-panel">
        <strong className="dev-ai-title">AI Chat 테스트</strong>
        <p className="dev-ai-note">
          {kind === 'member'
            ? '회원 세션 — AI 직원과의 대화 화면이 바로 열립니다.'
            : '게스트/비로그인 세션 — 로그인 유도 화면이 열립니다.'}
        </p>

        <div className="dev-ai-fields">
          <label className="dev-ai-field">
            boothId
            <input
              type="number"
              value={boothId}
              onChange={(e) => setBoothId(Number(e.target.value))}
            />
          </label>
          <label className="dev-ai-field">
            agentId
            <input
              type="number"
              value={agentId}
              onChange={(e) => setAgentId(Number(e.target.value))}
            />
          </label>
        </div>

        <button
          type="button"
          className="dev-ai-cta"
          onClick={() => openOverlay('AI_CHAT', { boothId, agentId })}
        >
          AI 직원과 대화
        </button>
      </div>

      <OverlayHost />
    </div>
  );
}
