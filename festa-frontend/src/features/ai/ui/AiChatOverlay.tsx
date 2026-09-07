// AI 직원 대화 Overlay — Overlay Family 재사용 (S15P21A604-406).
// 실서버 SSE 결선 (S15P21A604-189) — Conversation 생성 → 메시지 스트리밍을 실제 AI 서버에
// 붙인다. 파서(entities/conversation/stream.parser)는 mock 시절 그대로 재사용 — SSE 와이어
// 텍스트를 소비하는 형태(AsyncGenerator<string>)가 같아서 소스만 mock→real로 바뀐다.
import { useEffect, useRef, useState } from 'react';
import { closeOverlay } from '../../../shared/types/overlay';
import { createConversation, isAiHttpError, streamMessage } from '../../../entities/conversation/api';
import {
  describeHttpError,
  describeProtocolError,
  describeSequenceGap,
  describeSseError,
  shouldResetConversation,
  type StreamErrorDescription,
} from '../../../entities/conversation/errorMessages';
import { createSseParser } from '../../../entities/conversation/stream.parser';
import { SseProtocolError } from '../../../entities/conversation/stream.types';
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
  error?: StreamErrorDescription;
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

  async function ask(question: string) {
    if (busy || question.trim() === '') return;
    if (payload.agentId === undefined) {
      setTurns((t) => [
        ...t,
        { role: 'user', text: question },
        { role: 'agent', text: '', error: { headline: 'AI 직원 정보를 확인할 수 없습니다.', retryable: false } },
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
          setLastAgentTurn({
            error: isAiHttpError(e)
              ? describeHttpError(e)
              : { headline: '대화를 시작할 수 없습니다.', retryable: true },
          });
          return;
        }
      }

      const parser = createSseParser();
      let acc = '';
      const sources: string[] = [];
      let expectedSequence = 0;
      const apply = (done: boolean) => {
        setTurns((t) => {
          const next = [...t];
          next[next.length - 1] = { role: 'agent', text: acc, streaming: !done, sources: [...sources] };
          return next;
        });
      };

      try {
        streamLoop: for await (const chunk of streamMessage(activeConversationId, question)) {
          for (const ev of parser.push(chunk)) {
            // sequence는 무결성 검증 값이다(재개 offset 아님) — 결번은 잘린 응답으로 안내한다(#32 §3)
            if (ev.sequence !== expectedSequence) {
              setLastAgentTurn({ text: acc, sources: [...sources], error: describeSequenceGap() });
              break streamLoop;
            }
            expectedSequence += 1;

            if (ev.type === 'token') {
              acc += ev.delta;
              apply(false);
            } else if (ev.type === 'source') {
              sources.push(ev.title);
            } else if (ev.type === 'done') {
              apply(true);
              break streamLoop;
            } else if (ev.type === 'error') {
              setLastAgentTurn({ text: acc, sources: [...sources], error: describeSseError(ev) });
              break streamLoop;
            }
          }
        }
      } catch (e) {
        if (e instanceof SseProtocolError) {
          setLastAgentTurn({ text: acc, sources: [...sources], error: describeProtocolError() });
        } else if (isAiHttpError(e)) {
          if (shouldResetConversation(e)) setConversationId(null);
          setLastAgentTurn({ text: acc, sources: [...sources], error: describeHttpError(e) });
        } else {
          throw e;
        }
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
              <div className={'ai-bubble' + (t.error !== undefined ? ' ai-bubble-error' : '')}>
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
                {t.error !== undefined && (
                  <div className="ai-error">
                    <span className="ai-error-message">
                      {t.error.headline}
                      {t.error.retryAfterSeconds !== undefined && ` (${t.error.retryAfterSeconds}초 후)`}
                    </span>
                    {t.error.retryable && (
                      <button
                        type="button"
                        className="ov-btn ai-retry"
                        disabled={busy}
                        onClick={() => void ask(turns[i - 1]?.text ?? '')}
                      >
                        다시 시도
                      </button>
                    )}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </OverlayFrame>
  );
}
