// AI 직원 대화 테스트 화면 — DEV ONLY (프로덕션 라우트에서 제외, app/router/index.tsx 참고).
// features/ai/AiChatOverlay(목업 — Overlay Family 재사용)와는 완전히 별개의 독립 화면이다.
// World·Unity mock·Overlay Bus를 거치지 않고 boothId·agentId를 바로 바꿔가며 실 RAG 서버와
// 왕복 테스트하려는 도구라서 목업과 갈라 뒀다 — 목업 쪽 변경(디자인 교체 등)에 영향받지 않는다.
import { useState } from 'react';
import { useSession } from '../../features/auth/model/session';
import { DevAiChatConversation } from './DevAiChatConversation';
import { DevAiChatGuestGate } from './DevAiChatGuestGate';
import './devAiChatPage.css';

export function DevAiChatPage() {
  const { kind } = useSession();
  const isMember = kind === 'member';
  const [boothId, setBoothId] = useState(1);
  const [agentId, setAgentId] = useState(1);
  const [started, setStarted] = useState(false);

  // 화면 2·3 — 시작 후에는 로그인 상태에 따라 둘 중 하나만 그린다.
  if (started) {
    return isMember ? (
      <DevAiChatConversation boothId={boothId} agentId={agentId} onExit={() => setStarted(false)} />
    ) : (
      <DevAiChatGuestGate onExit={() => setStarted(false)} />
    );
  }

  // 화면 1 — 빈 화면 + 진입 버튼.
  return (
    <div className="dac-entry">
      <span className="dac-badge">DEV ONLY · 제품 화면 아님</span>

      <div className="dac-entry-panel">
        <strong className="dac-entry-title">AI Chat 테스트</strong>
        <p className="dac-entry-note">
          {isMember
            ? '회원 세션 — AI 직원과의 대화 화면이 열립니다.'
            : '게스트/비로그인 세션 — 로그인 유도 화면이 열립니다.'}
        </p>

        <div className="dac-entry-fields">
          <label className="dac-entry-field">
            boothId
            <input type="number" value={boothId} onChange={(e) => setBoothId(Number(e.target.value))} />
          </label>
          <label className="dac-entry-field">
            agentId
            <input type="number" value={agentId} onChange={(e) => setAgentId(Number(e.target.value))} />
          </label>
        </div>

        <button type="button" className="dac-entry-cta" onClick={() => setStarted(true)}>
          AI 직원과 대화
        </button>
      </div>
    </div>
  );
}
