// AI 직원 대화 Overlay — Overlay Family 재사용 (S15P21A604-406).
// AI 서버 conversation API(SSE)는 미구현이라 여기서는 기존 mock 스트림(entities/conversation)만
// 소비한다. 워커·계약 로직은 AI 파트 소관이라 건드리지 않는다 — 시각 정합과 상태 표현 범위.
import { useEffect, useRef, useState } from 'react';
import { closeOverlay } from '../../../shared/types/overlay';
import { mockStreamSuccess } from '../../../entities/conversation/stream.mock';
import { createSseParser } from '../../../entities/conversation/stream.parser';
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import './aiChatOverlay.css';

interface Props {
  payload: { boothId: number; agentId?: number };
}

interface Turn {
  role: 'user' | 'agent';
  text: string;
  streaming?: boolean;
  sources?: string[];
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

// 목업 답변 토큰 — 실제 답변은 AI 서버가 만든다(008). 여기서는 스트리밍 표현만 보여 준다.
function mockAnswerFor(question: string): string[] {
  if (question.includes('기술')) {
    return ['이 부스는 ', 'React 와 Unity WebGL 을 ', '함께 쓰는 구조로 만들었어요. ', '자세한 내용은 전시 자료에 정리돼 있습니다.'];
  }
  if (question.includes('팀')) {
    return ['다섯 명이 ', '프론트엔드·백엔드·Unity·AI 로 나눠 ', '만들고 있습니다.'];
  }
  return ['부스에 등록된 자료를 찾아봤어요. ', '"', question, '"에 대해서는 ', '전시 중인 프로젝트 소개에서 확인하실 수 있습니다.'];
}

export function AiChatOverlay({ payload }: Props) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight });
  }, [turns]);

  async function ask(question: string) {
    if (busy || question.trim() === '') return;
    setBusy(true);
    setDraft('');
    setTurns((t) => [...t, { role: 'user', text: question }, { role: 'agent', text: '', streaming: true }]);
    try {
      // mock SSE 와이어 → 실제 파서(stream.parser)로 읽는다 — 실 서버가 오면 이 소스만 교체된다
      const parser = createSseParser();
      let acc = '';
      const sources: string[] = [];
      const apply = (done: boolean) => {
        setTurns((t) => {
          const next = [...t];
          next[next.length - 1] = { role: 'agent', text: acc, streaming: !done, sources: [...sources] };
          return next;
        });
      };
      for await (const chunk of mockStreamSuccess(mockAnswerFor(question))) {
        for (const ev of parser.push(chunk)) {
          if (ev.type === 'token') acc += ev.delta;
          if (ev.type === 'source') sources.push(ev.title);
          apply(ev.type === 'done');
          // 토큰이 한 번에 쏟아지면 스트리밍처럼 보이지 않는다 — 목업에서만 살짝 늦춘다
          if (ev.type === 'token') await new Promise((r) => setTimeout(r, 45));
        }
      }
      for (const ev of parser.flush()) {
        if (ev.type === 'token') acc += ev.delta;
        apply(true);
      }
      apply(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <OverlayFrame
      title="AI 직원"
      subtitle={'부스 #' + payload.boothId}
      size="l"
      icon={IcAgent}
      onClose={closeOverlay}
      status={<span className="ov-note">부스 자료를 근거로 답합니다 · 준비 중인 기능입니다</span>}
      footer={
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
              </div>
            </div>
          ))}
        </div>
      )}
    </OverlayFrame>
  );
}
