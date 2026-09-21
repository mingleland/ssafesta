// AI 직원 대화 Overlay — Overlay Family 재사용 (S15P21A604-406).
// SSE 렌더링(S15P21A604-182)의 token/source 누적·error/sequence 결번 처리는
// entities/conversation/stream.consumer(consumeSseStream)에 위임한다. 이 파일(S15P21A604-189)은
// 실서버 결선만 담당 — Conversation 생성 → streamMessage로 얻은 SSE 본문을 그 consumer에 넘긴다.
// 종료 처리(S15P21A604-516): 오버레이가 사라지면 진행 중 스트림을 끊고 Conversation 원문을
// 즉시 삭제한다. 닫기 핸들러가 아니라 **언마운트**에 건다 — OverlayHost 가 타입별로 컴포넌트를
// 갈아끼우므로 Esc·배경 클릭·X·외부 closeOverlay()·다른 오버레이 전환이 전부 여기로 수렴한다.
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { facadeApi } from '../../../entities/booth/facadeApi.select';
import { closeOverlay } from '../../../shared/types/overlay';
import { closeConversation, createConversation, isAiHttpError, streamMessage } from '../../../entities/conversation/api';
import { describeHttpError, shouldResetConversation } from '../../../entities/conversation/errorMessages';
import { consumeSseStream } from '../../../entities/conversation/stream.consumer';
import type { SseConsumptionStatus } from '../../../entities/conversation/stream.consumer';
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';
import { useSession } from '../../auth/model/session';
import { saveReturnTo } from '../../auth/model/returnTo';
import { aiHandoffContext } from '../../consultation/model/startContext';
import { requestConsultation, useVisitorConsultation } from '../../consultation/model/visitor';
import './aiChatOverlay.css';
import { renderMarkdown } from './renderMarkdown';
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

const SUGGESTIONS = ['어떤 프로젝트인가요?', '누구를 대상으로 한 서비스인가요?', '기술 스택이 궁금해요'];

export function AiChatOverlay({ payload }: Props) {
  const { kind } = useSession();
  const isMember = kind === 'member';
  const location = useLocation();
  const navigate = useNavigate();
  const consultation = useVisitorConsultation();
  // 제목에는 사람이 읽는 부스 이름을 쓴다 — payload.boothId 는 DB PK 라 그대로 노출하면
  // 슬롯 번호처럼 읽힌다(S15P21A604-823). 방문자도 부를 수 있는 GET /booths/{id} 로 이름을 얻는다.
  // 실패하면 조용히 이름 없이 '부스'로만 둔다(제목 장식이라 대화 흐름을 막지 않는다).
  const [boothName, setBoothName] = useState<string | null>(null);
  // 사람 상담 연결 허용 여부 (S15P21A604-910). handoffEnabled === false 인 부스에서만 상담 버튼을
  // 숨긴다 — 값 로딩 전·undefined(구버전 서버)·getBooth 실패는 버튼을 유지한다(무해 degrade, -914 계약).
  // 조용히 기본값으로 감추면 정상 부스에서 상담 진입이 사라진다(CLAUDE.md 실패처리 규칙).
  const [handoffEnabled, setHandoffEnabled] = useState<boolean | undefined>(undefined);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  // null = 아직 Conversation 없음(첫 질문에서 생성) — 403/404 오류 후에도 null로 되돌려
  // 다음 질문이 새 Conversation을 만들게 한다(entities/conversation/errorMessages 참고)
  const [conversationId, setConversationId] = useState<string | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  // 언마운트 cleanup 은 마운트 시점 클로저라 state 를 stale 하게 본다 — 정리에 쓸 값은 ref 로 따로 든다.
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
      // fire-and-forget — 화면이 이미 사라져 보여줄 곳이 없다. 실패해도 30분 유휴 TTL 이 지우고,
      // /ai/* 라우팅이 아직 인프라 대기라(GitLab #144) 호출이 안 나갈 수도 있다. 그때도 조용해야 한다.
      if (id !== null) void closeConversation(id).catch(() => {});
    },
    [],
  );

  useEffect(() => {
    bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight });
  }, [turns]);

  useEffect(() => {
    let alive = true;
    void facadeApi
      .getBooth(payload.boothId)
      .then((booth) => {
        if (alive) {
          setBoothName(booth.name);
          setHandoffEnabled(booth.handoffEnabled);
        }
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [payload.boothId]);

  // 게스트·비로그인은 Conversation 생성 진입점 자체를 보이지 않는다(spec 008 FR-027·SC-011).
  function goLogin() {
    saveReturnTo(location.pathname + location.search + location.hash);
    closeOverlay();
    navigate('/login');
  }

  function setLastAgentTurn(update: Partial<Turn>) {
    setTurns((t) => {
      const next = [...t];
      const last = next[next.length - 1];
      next[next.length - 1] = { ...last, streaming: false, ...update };
      return next;
    });
  }

  function showHttpError(e: unknown, question: string, fallbackHeadline: string) {
    // 우리가 끊은 것이다 — 오류가 아니다. 오버레이는 이미 사라지는 중이라 그릴 화면도 없다.
    // 판정 관용구는 game-studio/host/GameOverlay.tsx 와 같은 것을 쓴다(S15P21A604-516).
    if (e instanceof DOMException && e.name === 'AbortError') return;
    if (isAiHttpError(e)) {
      if (shouldResetConversation(e)) rememberConversationId(null);
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

  // 게스트는 footer·본문 어디에도 진입점(제안 칩·입력창)을 보지 못하지만, 여기서도 한 번 더
  // 막는다(spec 008 FR-027) — Conversation 생성·검색·LLM 호출이 실제로 나가는 지점이라서다.
  async function ask(question: string) {
    if (!isMember || busy || question.trim() === '') return;
    if (payload.agentId === undefined) {
      setTurns((t) => [
        ...t,
        { role: 'user', text: question },
        { role: 'agent', text: '', status: 'error', errorMessage: 'AI 직원 정보를 확인할 수 없습니다.', retryable: false },
      ]);
      return;
    }
    const agentId = payload.agentId;

    // 질문마다 새 컨트롤러다 — 이전 질문의 abort 상태를 물려받지 않는다.
    const controller = new AbortController();
    abortRef.current = controller;

    setBusy(true);
    setDraft('');
    setTurns((t) => [...t, { role: 'user', text: question }, { role: 'agent', text: '', streaming: true }]);
    try {
      let activeConversationId = conversationId;
      if (activeConversationId === null) {
        try {
          const handle = await createConversation(payload.boothId, agentId);
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

  // 사람 상담 에스컬레이션 (spec 011 FR-005 · S15P21A604-416).
  // 대상 부스는 이 대화의 boothId 다 — AI_AGENT_INTERACT 가 준 값이라 FE 가 추측하지 않는다.
  // 게스트는 요청할 수 없다(FR-014) — 진입점에서 막는 것이 이 기능의 정책이다.
  const consultationInProgress =
    consultation.phase === 'requesting' ||
    consultation.phase === 'waiting' ||
    consultation.phase === 'active';

  function escalateToHuman() {
    // 대화 id 만 넘긴다 (#133 확정 계약, S15P21A604-519). 요약은 서버가 이 id 로 FastAPI 에
    // 청해 만든다 — 마지막 AI 답변을 잘라 보내던 방식은 폐기했다. 그 텍스트는 요약이 아니라
    // 요약의 재료 한 조각이었고, 직원이 보는 요약의 정본은 서버여야 한다(FR-012).
    // 대화를 아직 시작하지 않았으면 undefined 이고, 그때 요약은 null 이 된다.
    void requestConsultation(aiHandoffContext(payload.boothId, conversationIdRef.current ?? undefined));
    // 상담 화면으로 바꾼다. 같은 부스라 Visitor 슬롯을 그대로 넘겨받는다.
    openVisitorOverlay('CONSULTATION', { boothId: payload.boothId });
  }

  return (
    <OverlayFrame
      title="AI 직원"
      subtitle={boothName ?? '부스'}
      size="l"
      icon={IcAgent}
      onClose={closeOverlay}
      status={
        <span className="ov-note">
          {!isMember
            ? 'AI 직원과의 대화는 소셜 로그인 회원만 이용할 수 있습니다'
            : handoffEnabled !== false
              ? '원하는 답을 못 찾으면 사람 상담을 요청할 수 있습니다'
              : ''}
        </span>
      }
      footer={
        isMember ? (
          <>
            {handoffEnabled !== false && (
              <Tooltip content={consultationInProgress ? '이미 진행 중인 상담이 있습니다' : null}>
                <button
                  type="button"
                  className="ov-btn ai-escalate"
                  disabled={consultationInProgress}
                  onClick={escalateToHuman}
                >
                  사람 상담 요청
                </button>
              </Tooltip>
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
        ) : undefined
      }
    >
      {!isMember ? (
        <div className="festa-overlay-state">
          <strong>로그인이 필요합니다</strong>
          <p className="ov-note">AI 직원과의 대화는 소셜 로그인 회원만 이용할 수 있어요.</p>
          <button type="button" className="ov-btn ov-btn-primary" onClick={goLogin}>
            로그인하러 가기
          </button>
        </div>
      ) : turns.length === 0 ? (
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
                {t.role === 'agent' ? renderMarkdown(t.text) : t.text}
                {t.streaming && <span className="ai-caret" />}
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
