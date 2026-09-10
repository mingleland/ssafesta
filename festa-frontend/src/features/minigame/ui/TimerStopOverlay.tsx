// 타이밍 스톱 미니게임 — Unity 에서 이관한 화면 (S15P21A604-601, GitLab #166).
//
// Unity 는 이제 F 상호작용 이벤트만 보내고, 게임 진행·서버 판정 호출·결과 표시를 여기가 맡는다.
//
// **여기서 하지 않는 것** — ESC 리스너(WorldPage 단일 중재자), `SetInputLocked` 직접 호출
// (UnityHost 가 worldScreen 을 보고 민다), 배타 처리(worldScreen.clearOthers), focus 반환
// (OverlayFrame). 그 넷은 이 오버레이가 `openVisitorOverlay` 로 열렸다는 사실만으로 따라온다.
import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { closeOverlay } from '../../../shared/types/overlay';
import { isApiError } from '../../../shared/api/client';
import { startTimerStopSession, submitTimerStopResult } from '../../../entities/minigame/api';
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { useSession } from '../../auth/model/session';
import { saveReturnTo } from '../../auth/model/returnTo';
import { elapsedSeconds, IDLE, toErrorReason } from '../model/timerStop';
import type { TimerStopPhase } from '../model/timerStop';
import { describeResult, formatErrorSeconds } from './resultCopy';
import './timerStop.css';

const IcTimer = (
  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2">
    <circle cx="12" cy="13" r="8" />
    <path d="M12 9v4l2.5 2M9 2h6" strokeLinecap="round" />
  </svg>
);

/** 기본 동작이 이미 같은 핸들러를 부르는 자리 — 전역 Space 를 여기서 두 번 태우지 않는다 */
const INTERACTIVE = 'button, input, textarea, select, a[href], [role="button"], [contenteditable]';

/**
 * payload 를 받지 않는다. Bus 에는 `{ gameId, machineId }` 가 실려 있지만(dispatcher) 화면이 읽을
 * 것이 없다 — 내장 미니게임이 `TIMER_STOP` 하나뿐이라 `gameId` 로 갈릴 화면이 없고, `machineId` 는
 * 애초에 FE 가 해석하지 않기로 한 값이다(#166). 종류가 늘면 그때 `gameId` 를 받아 갈린다.
 */
export function TimerStopOverlay() {
  const { kind } = useSession();
  const isMember = kind === 'member';
  const location = useLocation();
  const navigate = useNavigate();

  const [phase, setPhase] = useState<TimerStopPhase>(IDLE);
  const [elapsed, setElapsed] = useState(0);
  // 제출은 판마다 한 번이다. 상태 전이만으로 막으면 같은 tick 안의 연타가 두 번 들어간다.
  const submittedRef = useRef(false);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;

  function goLogin() {
    saveReturnTo(location.pathname + location.search + location.hash);
    closeOverlay();
    navigate('/login');
  }

  /**
   * 정지 → 즉시 제출. 사이에 확인 단계를 넣지 않는다 — 서버가 발급 시각부터의 경과와 대조하므로
   * 지체하면 세션이 소진된다(model/timerStop.ts 주석).
   */
  const stopAndSubmit = useCallback(async (at: number) => {
    const current = phaseRef.current;
    if (current.kind !== 'RUNNING' || submittedRef.current) return;
    submittedRef.current = true;

    const stoppedSeconds = elapsedSeconds(current.startedAt, at);
    setPhase({ kind: 'SUBMITTING', session: current.session, stoppedSeconds });

    try {
      const result = await submitTimerStopResult(current.session.sessionId, stoppedSeconds);
      setPhase({ kind: 'RESULT', result, stoppedSeconds });
    } catch (cause) {
      // 404 는 오류가 아니라 결과의 한 종류다 — 세션이 만료됐거나 남의 것이다
      if (isApiError(cause) && cause.status === 404) {
        setPhase({ kind: 'RESULT_EXPIRED' });
        return;
      }
      setPhase({ kind: 'ERROR', reason: toErrorReason(isApiError(cause) ? cause.status : undefined) });
    }
  }, []);

  /** [시작] 을 누른 뒤에 발급한다. 201 이 오면 그 자리에서 달리기 시작한다 */
  const start = useCallback(async () => {
    if (!isMember) return;
    const current = phaseRef.current;
    if (current.kind === 'ISSUING_SESSION' || current.kind === 'RUNNING' || current.kind === 'SUBMITTING') return;

    submittedRef.current = false;
    setElapsed(0);
    setPhase({ kind: 'ISSUING_SESSION' });
    try {
      const session = await startTimerStopSession();
      setPhase({ kind: 'RUNNING', session, startedAt: performance.now() });
    } catch (cause) {
      setPhase({ kind: 'ERROR', reason: toErrorReason(isApiError(cause) ? cause.status : undefined) });
    }
  }, [isMember]);

  // 경과 표시. rAF 가 멈춰도 계산에 영향이 없다 — 값은 performance.now() 차이로 만든다.
  useEffect(() => {
    if (phase.kind !== 'RUNNING') return;
    let raf = 0;
    const tick = () => {
      const now = performance.now();
      setElapsed(elapsedSeconds(phase.startedAt, now));
      // 제한 시간을 넘기면 자동 정지·즉시 제출한다. 서버가 timedOut 으로 200 을 주므로 실패로
      // 화면을 막지 않고, 여기서 붙들고 있으면 오히려 허용오차를 넘겨 세션이 소진된다.
      if (now - phase.startedAt > phase.session.failAfterSeconds * 1000) {
        void stopAndSubmit(now);
        return;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [phase, stopAndSubmit]);

  // 탭이 뒤로 갔다 돌아온 경우. **숨는 것 자체를 실패로 보지 않는다** — 그 사이 rAF 만 멈추고
  // 시계는 계속 갔다. 돌아온 시점에 이미 제한을 넘겼으면 그 자리에서 제출한다(늦을수록 허용오차가
  // 위험해진다). 넘지 않았으면 아무것도 하지 않고 계속 달린다.
  useEffect(() => {
    function onVisible() {
      if (document.hidden) return;
      const current = phaseRef.current;
      if (current.kind !== 'RUNNING') return;
      const now = performance.now();
      if (now - current.startedAt > current.session.failAfterSeconds * 1000) void stopAndSubmit(now);
    }
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [stopAndSubmit]);

  // Space 로도 시작·정지한다. 버튼은 정상적으로 focus 가능하게 두고(키보드 사용자가 Tab 으로
  // 도달해야 한다), 그 자리에 focus 가 있으면 기본 동작이 같은 핸들러를 이미 부르므로 여기서 빠진다.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.code !== 'Space' || event.repeat) return;
      if ((document.activeElement as HTMLElement | null)?.closest(INTERACTIVE)) return;
      event.preventDefault();
      const current = phaseRef.current;
      if (current.kind === 'RUNNING') void stopAndSubmit(performance.now());
      else if (current.kind === 'IDLE') void start();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [start, stopAndSubmit]);

  const target = phase.kind === 'RUNNING' || phase.kind === 'SUBMITTING' ? phase.session.targetSeconds : null;

  return (
    <OverlayFrame
      title="타이밍 스톱"
      subtitle={target === null ? '목표 시간에 맞춰 멈추세요' : `목표 ${target.toFixed(3)}초`}
      size="m"
      icon={IcTimer}
      onClose={closeOverlay}
      status={
        <span className="ov-note">
          {isMember ? '보상은 서버가 판정합니다 · 하루 한도가 있어요' : '보상 게임은 소셜 로그인 회원만 참여할 수 있습니다'}
        </span>
      }
      footer={isMember ? <Footer phase={phase} onStart={() => void start()} onStop={() => void stopAndSubmit(performance.now())} /> : undefined}
    >
      {!isMember ? (
        <div className="festa-overlay-state">
          <strong>로그인이 필요합니다</strong>
          <p className="ov-note">보상 게임은 소셜 로그인 회원만 참여할 수 있어요.</p>
          <button type="button" className="ov-btn ov-btn-primary" onClick={goLogin}>
            로그인하러 가기
          </button>
        </div>
      ) : (
        <Body phase={phase} elapsed={elapsed} onRetry={() => void start()} />
      )}
    </OverlayFrame>
  );
}

function Body({ phase, elapsed, onRetry }: { phase: TimerStopPhase; elapsed: number; onRetry: () => void }) {
  if (phase.kind === 'IDLE') {
    return (
      <div className="ts-stage">
        <p className="ts-guide">시작을 누르면 목표 시간이 정해집니다. 그 시간에 맞춰 멈추세요.</p>
        <span className="ts-clock ts-clock-idle">0.000</span>
        <p className="ov-note">Space 로도 시작·정지할 수 있어요.</p>
      </div>
    );
  }

  if (phase.kind === 'ISSUING_SESSION') {
    return (
      <div className="ts-stage">
        <span className="ts-clock ts-clock-idle">0.000</span>
        <p className="ov-note">판을 준비하는 중...</p>
      </div>
    );
  }

  if (phase.kind === 'RUNNING' || phase.kind === 'SUBMITTING') {
    const shown = phase.kind === 'RUNNING' ? elapsed : phase.stoppedSeconds;
    return (
      <div className="ts-stage">
        <span className="ts-clock" aria-live="off">{shown.toFixed(3)}</span>
        <p className="ov-note">
          {phase.kind === 'RUNNING' ? `${phase.session.failAfterSeconds.toFixed(3)}초를 넘기면 실패예요` : '결과를 보내는 중...'}
        </p>
      </div>
    );
  }

  if (phase.kind === 'RESULT_EXPIRED') {
    return (
      <div className="festa-overlay-state">
        <strong>게임 세션이 만료됐어요</strong>
        <p className="ov-note">다시 시작해 주세요.</p>
        <button type="button" className="ov-btn ov-btn-primary" onClick={onRetry}>다시 하기</button>
      </div>
    );
  }

  if (phase.kind === 'ERROR') {
    const headline =
      phase.reason === 'MEMBER_ONLY' ? '보상 게임은 회원만 참여할 수 있어요'
      : phase.reason === 'VALIDATION' ? '결과를 보내지 못했습니다'
      : '문제가 생겼어요';
    return (
      <div className="festa-overlay-state">
        <strong>{headline}</strong>
        {/* VALIDATION 은 자동으로 다시 달리지 않는다 — 같은 상태로 재개하면 같은 실패를 반복한다 */}
        <button type="button" className="ov-btn ov-btn-primary" onClick={onRetry}>다시 하기</button>
      </div>
    );
  }

  const copy = describeResult(phase.result);
  return (
    <div className="ts-stage">
      <span className="ts-clock ts-clock-done">{phase.stoppedSeconds.toFixed(3)}</span>
      <strong className={`ts-headline ts-tone-${copy.tone}`}>{copy.headline}</strong>
      {copy.detail !== undefined && <p className="ov-note">{copy.detail}</p>}
      {/* 오차는 서버 값이다 — 위 초시계(클라이언트 측정)와 갈릴 수 있고, 판정의 근거는 이쪽이다 */}
      <p className="ts-error">오차 {formatErrorSeconds(phase.result.errorSeconds)}</p>
    </div>
  );
}

function Footer({ phase, onStart, onStop }: { phase: TimerStopPhase; onStart: () => void; onStop: () => void }) {
  if (phase.kind === 'RUNNING') {
    return <button type="button" className="ov-btn ov-btn-primary" onClick={onStop}>멈추기</button>;
  }
  if (phase.kind === 'ISSUING_SESSION' || phase.kind === 'SUBMITTING') {
    return <button type="button" className="ov-btn ov-btn-primary" disabled>진행 중...</button>;
  }
  if (phase.kind === 'IDLE') {
    return <button type="button" className="ov-btn ov-btn-primary" onClick={onStart}>시작</button>;
  }
  return <button type="button" className="ov-btn ov-btn-primary" onClick={onStart}>다시 하기</button>;
}
