// 회원 대화 화면 — DEV ONLY, AiChatOverlay(features/ai)와 완전히 별개 구현이다.
// UI(레이아웃·JSX·CSS)는 목업을 재사용하지 않되, RAG 왕복 자체는 실 서버 계약이라
// entities/conversation(api·stream.consumer·errorMessages)만 그대로 소비한다 — 그 계약을
// 다시 구현하면 실 서버와 다르게 동작할 위험만 생긴다.
import { useEffect, useRef, useState } from 'react';
import { closeConversation, createConversation, isAiHttpError, streamMessage } from '../../entities/conversation/api';
import { describeHttpError, shouldResetConversation } from '../../entities/conversation/errorMessages';
import { consumeSseStream } from '../../entities/conversation/stream.consumer';
import type { SseConsumptionStatus } from '../../entities/conversation/stream.consumer';

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

// 예시 질문 — 채팅창 위(입력창 바로 위)에 항상 띄운다. 대화가 시작된 뒤에도 계속 다른 예시로
// 이어 물어볼 수 있게 남겨 둔다(요청 참고 이미지: 입력창 위 pill 형 quick-reply).
const SUGGESTIONS = ['어떤 프로젝트를 전시하나요?', '팀을 소개해 주세요', '기술 스택이 궁금해요', '준비 기간이 얼마나 걸렸나요?'];

export function DevAiChatConversation({
  boothId,
  agentId,
  onExit,
}: {
  boothId: number;
  agentId: number;
  onExit: () => void;
}) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const conversationIdRef = useRef<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  function rememberConversationId(id: string | null) {
    conversationIdRef.current = id;
    setConversationId(id);
  }

  useEffect(
    () => () => {
      abortRef.current?.abort();
      const id = conversationIdRef.current;
      if (id !== null) void closeConversation(id).catch(() => {});
    },
    [],
  );

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
    if (e instanceof DOMException && e.name === 'AbortError') return;
    if (isAiHttpError(e)) {
      if (shouldResetConversation(e)) rememberConversationId(null);
      const description = describeHttpError(e);
      setLastAgentTurn({
        status: 'error',
        errorMessage:
          description.headline + (description.retryAfterSeconds !== undefined ? ` (${description.retryAfterSeconds}초 후)` : ''),
        retryable: description.retryable,
        retryQuestion: question,
      });
      return;
    }
    console.warn('[Dev AI Chat] 스트림 처리 실패', e instanceof Error ? e.name : 'UnknownError');
    setLastAgentTurn({ status: 'error', errorMessage: fallbackHeadline, retryable: true, retryQuestion: question });
  }

  async function ask(question: string) {
    if (busy || question.trim() === '') return;
    const controller = new AbortController();
    abortRef.current = controller;

    setBusy(true);
    setDraft('');
    setTurns((t) => [...t, { role: 'user', text: question }, { role: 'agent', text: '', streaming: true }]);
    try {
      let activeConversationId = conversationId;
      if (activeConversationId === null) {
        try {
          const handle = await createConversation(boothId, agentId);
          activeConversationId = handle.conversationId;
          rememberConversationId(activeConversationId);
        } catch (e) {
          showHttpError(e, question, '대화를 시작할 수 없습니다.');
          return;
        }
      }

      try {
        await consumeSseStream(streamMessage(activeConversationId, question, controller.signal), (snapshot) => {
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

  return (
    <div className="dac-screen">
      <header className="dac-head">
        <button type="button" className="dac-back" onClick={onExit}>
          ← 나가기
        </button>
        <span className="dac-head-title">AI 직원 · 부스 #{boothId} · agent #{agentId}</span>
        <span className="dac-head-status">
          {conversationId === null ? '대화 시작 전' : `conversationId: ${conversationId}`}
        </span>
      </header>

      <div className="dac-thread" ref={bodyRef}>
        {turns.length === 0 && <p className="dac-empty">질문을 입력하면 실 RAG 서버로 요청이 나갑니다.</p>}
        {turns.map((t, i) => (
          <div key={i} className={'dac-turn dac-turn-' + t.role}>
            <span className="dac-role">{t.role === 'user' ? '나' : 'AI'}</span>
            <div className="dac-bubble">
              {t.text}
              {t.streaming && <span className="dac-caret" />}
              {t.sources !== undefined && t.sources.length > 0 && !t.streaming && (
                <div className="dac-sources">
                  {t.sources.map((s) => (
                    <span key={s} className="dac-chip">
                      {s}
                    </span>
                  ))}
                </div>
              )}
              {(t.status === 'error' || t.status === 'truncated') && (
                <div className="dac-error" role="alert">
                  {t.errorMessage}
                  {t.retryable && t.retryQuestion !== undefined && (
                    <button type="button" className="dac-retry" disabled={busy} onClick={() => void ask(t.retryQuestion!)}>
                      다시 시도
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      <div className="dac-suggestions" role="group" aria-label="예시 질문">
        {SUGGESTIONS.map((s) => (
          <button key={s} type="button" className="dac-suggestion" disabled={busy} onClick={() => void ask(s)}>
            {s}
          </button>
        ))}
      </div>

      <form
        className="dac-composer"
        onSubmit={(e) => {
          e.preventDefault();
          void ask(draft);
        }}
      >
        <input
          className="dac-input"
          type="text"
          value={draft}
          placeholder="부스에 대해 물어보세요"
          onChange={(e) => setDraft(e.target.value)}
          disabled={busy}
        />
        <button type="submit" className="dac-send" disabled={busy || draft.trim() === ''}>
          보내기
        </button>
      </form>
    </div>
  );
}
