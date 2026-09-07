// AI 직원 대화 Overlay — Overlay Family 재사용 (S15P21A604-406).
// SSE 렌더링(S15P21A604-182)의 token/source 누적·error/sequence 결번 처리는
// entities/conversation/stream.consumer(consumeSseStream)에 위임한다. 이 파일(S15P21A604-189)은
// 실서버 결선만 담당 — Conversation 생성 → streamMessage로 얻은 SSE 본문을 그 consumer에 넘긴다.
import { useEffect, useRef, useState } from 'react';
import { closeOverlay } from '../../../shared/types/overlay';
import { createConversation, isAiHttpError, streamMessage } from '../../../entities/conversation/api';
import { describeHttpError, shouldResetConversation } from '../../../entities/conversation/errorMessages';
import { consumeSseStream } from '../../../entities/conversation/stream.consumer';
import type { SseConsumptionStatus } from '../../../entities/conversation/stream.consumer';
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { useSession } from '../../auth/model/session';
import { aiHandoffContext } from '../../consultation/model/startContext';
import { requestConsultation, useVisitorConsultation } from '../../consultation/model/visitor';
import './aiChatOverlay.css';
import { openVisitorOverlay } from '../../world/model/worldScreen';

interface Props {
  payload: { boothId: number; agentId?: number };
}

interface Turn {
  role: 'user' | 'agent';
  text: string;
  streaming?: boolean;
  sources?: string[];
  status?: SseConsumptionStatus;
  errorMessage?: string;
  retryable?: boolean;
  retryQuestion?: string;
}

const IcAgent = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="4" y="7" width="16" height="12" rx="4" />
    <path d="M12 7V4" />
    <circle cx="9.5" cy="13" r="1.2" fill="currentColor" stroke="none" />
    <circle cx="14.5" cy="13" r="1.2" fill="currentColor" stroke="none" />
  </svg>
);

const SUGGESTIONS = ['어떤 프로젝트를 전시하나요?', '팀을 소개해 주세요', '기술 스택이 궁금해요'];

export function AiChatOverlay({ payload }: Props) {
  const { kind } = useSession();
  const consultation = useVisitorConsultation();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  // null = 아직 Conversation 없음(첫 질문에서 생성) — 403/404 오류 후에도 null로 되돌려
  // 다음 질문이 새 Conversation을 만들게 한다(entities/conversation/errorMessages 참고)
  const [conversationId, setConversationId] = useState<string | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight });
  }, [turns]);

  function setLastAgentTurn(update: Partial<Turn>) {
    setTurns((t) => {
      const next = [...t];
      const last = next[next.length - 1];
      next[next.length - 1] = { ...last, streaming: false, ...update };
      return next;
    });
  }

  function showHttpError(e: unknown, question: string, fallbackHeadline: string) {
    if (isAiHttpError(e)) {
      if (shouldResetConversation(e)) setConversationId(null);
      const description = describeHttpError(e);
      setLastAgentTurn({
        status: 'error',
        errorMessage:
          description.headline +
          (description.retryAfterSeconds !== undefined ? ` (${description.retryAfterSeconds}초 후)` : ''),
        retryable: description.retryable,
        retryQuestion: question,
      });
      return;
    }
    console.warn('[AI SSE] 스트림 처리 실패', e instanceof Error ? e.name : 'UnknownError');
    setLastAgentTurn({ status: 'error', errorMessage: fallbackHeadline, retryable: true, retryQuestion: question });
  }

  async function ask(question: string) {
    if (busy || question.trim() === '') return;
    if (payload.agentId === undefined) {
      setTurns((t) => [
        ...t,
        { role: 'user', text: question },
        { role: 'agent', text: '', status: 'error', errorMessage: 'AI 직원 정보를 확인할 수 없습니다.', retryable: false },
      ]);
      return;
    }
    const agentId = payload.agentId;

    setBusy(true);
    setDraft('');
    setTurns((t) => [...t, { role: 'user', text: question }, { role: 'agent', text: '', streaming: true }]);
    try {
      let activeConversationId = conversationId;
      if (activeConversationId === null) {
        try {
          const handle = await createConversation(payload.boothId, agentId);
          activeConversationId = handle.conversationId;
          setConversationId(activeConversationId);
        } catch (e) {
          showHttpError(e, question, '대화를 시작할 수 없습니다.');
          return;
        }
      }

      try {
        await consumeSseStream(streamMessage(activeConversationId, question), (snapshot) => {
          setTurns((t) => {
            const next = [...t];
            next[next.length - 1] = {
              role: 'agent',
              text: snapshot.text,
              streaming: snapshot.status === 'streaming',
              sources: snapshot.sources,
              status: snapshot.status,
              errorMessage: snapshot.errorMessage,
              retryable: snapshot.retryable,
              retryQuestion: question,
            };
            return next;
          });
        });
      } catch (e) {
        showHttpError(e, question, '응답을 처리하지 못했습니다. 다시 시도해 주세요.');
      }
    } finally {
      setBusy(false);
    }
  }

  // 사람 상담 에스컬레이션 (spec 011 FR-005 · S15P21A604-416).
  // 대상 부스는 이 대화의 boothId 다 — AI_AGENT_INTERACT 가 준 값이라 FE 가 추측하지 않는다.
  // 게스트는 요청할 수 없다(FR-014) — 진입점에서 막는 것이 이 기능의 정책이다.
  const isMember = kind === 'member';
  const consultationInProgress =
    consultation.phase === 'requesting' ||
    consultation.phase === 'waiting' ||
    consultation.phase === 'active';

  function escalateToHuman() {
    // 마지막 AI 답변을 Handoff Summary 로 넘긴다. 실 요약 생성은 AI 서버 몫이라(spec 011),
    // 여기서는 대화 맥락이 실제로 이어진다는 것만 계약으로 보인다 — 없으면 넘기지 않는다.
    const lastAgentTurn = [...turns].reverse().find((t) => t.role === 'agent' && !t.streaming);
    void requestConsultation(aiHandoffContext(payload.boothId, lastAgentTurn?.text));
    // 상담 화면으로 바꾼다. 같은 부스라 Visitor 슬롯을 그대로 넘겨받는다.
    openVisitorOverlay('CONSULTATION', { boothId: payload.boothId });
  }

  return (
    <OverlayFrame
      title="AI 직원"
      subtitle={'부스 #' + payload.boothId}
      size="l"
      icon={IcAgent}
      onClose={closeOverlay}
      status={
        <span className="ov-note">
          {isMember
            ? '원하는 답을 못 찾으면 사람 상담을 요청할 수 있습니다'
            : '부스 자료를 근거로 답합니다 · 사람 상담은 회원만 요청할 수 있습니다'}
        </span>
      }
      footer={
        <>
          {isMember && (
            <button
              type="button"
              className="ov-btn ai-escalate"
              disabled={consultationInProgress}
              title={consultationInProgress ? '이미 진행 중인 상담이 있습니다' : undefined}
              onClick={escalateToHuman}
            >
              사람 상담 요청
            </button>
          )}
        <form
          className="ai-composer"
          onSubmit={(e) => {
            e.preventDefault();
            void ask(draft);
          }}
        >
          <input
            className="ai-input"
            type="text"
            value={draft}
            placeholder="부스에 대해 물어보세요"
            onChange={(e) => setDraft(e.target.value)}
            disabled={busy}
          />
          <button type="submit" className="ov-btn ov-btn-primary" disabled={busy || draft.trim() === ''}>
            보내기
          </button>
        </form>
        </>
      }
    >
      {turns.length === 0 ? (
        <div className="ai-intro">
          <span className="ai-intro-badge">{IcAgent}</span>
          <strong>무엇이든 물어보세요</strong>
          <p className="ov-note">부스에 등록된 자료를 근거로 답합니다.</p>
          <div className="ai-suggestions">
            {SUGGESTIONS.map((s) => (
              <button key={s} type="button" className="ai-suggestion" onClick={() => void ask(s)}>
                {s}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <div className="ai-thread" ref={bodyRef}>
          {turns.map((t, i) => (
            <div key={i} className={'ai-turn ai-turn-' + t.role}>
              {t.role === 'agent' && <span className="ai-avatar">AI</span>}
              <div className="ai-bubble">
                {t.text}
                {t.streaming && <span className="ai-caret" />}
                {t.sources !== undefined && t.sources.length > 0 && !t.streaming && (
                  <span className="ai-sources">
                    {t.sources.map((s) => (
                      <span key={s} className="ov-chip">
                        {s}
                      </span>
                    ))}
                  </span>
                )}
                {(t.status === 'error' || t.status === 'truncated') && (
                  <span className="ai-stream-error" role="alert">
                    {t.errorMessage}
                    {t.retryable && t.retryQuestion !== undefined && (
                      <button
                        type="button"
                        className="ov-btn ai-retry"
                        disabled={busy}
                        onClick={() => void ask(t.retryQuestion!)}
                      >
                        다시 시도
                      </button>
                    )}
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </OverlayFrame>
  );
}
