// spec 013a WebGL Host — Unity 인스턴스를 안전하게 생성·종료하고 입장 게이트 신호(#31)·
// boot watchdog(-426)까지만 책임진다. 오버레이 렌더러(OverlayHost)·Interaction Dispatcher·016 E2E는
// 범위 밖 — 이 컴포넌트는 그 위에서 동작할 기반이다.
//
// 상태 수명주기 (#128 §3, -429): booting(watchdog 감시) → instance 확보 → waiting-gate(타임아웃 없음 —
// 로비 체류는 사용자 시간이다) → [Unity 가 월드 로드 시작을 알리면] preparing-world → ready.
// 실패는 boot 구간에서만 판정한다. 재시도는 새 boot attempt 로 새 watchdog 을 건다.
//
// 접속 상태(-432, #131)는 위 수명주기와 **다른 층**이다. 위는 "Unity 를 띄우는 중" 이고 이쪽은 "월드에
// 붙어 있는가" 라, ready 이후에도 백그라운드 복귀 등으로 끊길 수 있다. 재시도·백오프는 Unity 가 소유하고
// (WorldReconnector) 호스트는 상태 표시와 실패 후 복구 동선만 맡는다 — FE 에 재접속 타이머를 두지 않는다.
//
// waiting-gate 와 preparing-world 를 가르는 이유: 둘 다 "게이트 신호를 기다리는 중" 이지만 사용자에게는
// 전혀 다른 시간이다. 앞은 로비에서 아바타를 고르는 자기 시간이라 안내가 뜨면 방해고, 뒤는 main 씬 로드
// 50~84초 대기라 안내가 없으면 멈춘 것으로 오인한다. Unity 가 아직 시작 신호를 안 보내면 preparing-world
// 에 들어가지 않으므로 이 컴포넌트는 예전과 똑같이 동작한다.
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import {
  initUnityBridge,
  subscribeWorldConnectionState,
  subscribeWorldGateReady,
  subscribeWorldLoadStart,
} from '../bridge/events';
import type { WorldConnectionState } from '../bridge/events';
import { acquireUnitySession, releaseUnitySession, restartUnitySession } from './sessionManager';
import { syncAccessToken } from './authBridge';
import { syncInputLock } from './inputBridge';
import { getCurrentOverlay, subscribeOverlay } from '../../shared/types/overlay';
import { useSession } from '../../features/auth/model/session';
import { UNITY_BOOT_STALL_TIMEOUT_MS, WORLD_PREPARING_LONG_WAIT_MS } from '../../shared/config/unity';
import './unityHostStatus.css';
import type { UnityInstance } from './types';

type HostStatus = 'booting' | 'waiting-gate' | 'preparing-world' | 'ready' | 'failed';

// Unity WorldReconnector 가 보내는 사유 문자열 → 사용자 문구. 목록에 없는 값·빈 문자열(무응답)이 올 수 있고
// Unity 쪽이 사유를 늘려도 화면이 비지 않아야 하므로 fallback 을 반드시 둔다.
const DISCONNECT_REASONS: Record<string, string> = {
  INVALID_TOKEN: '로그인이 만료되었습니다. 다시 로그인해 주세요.',
  SERVER_FULL: '월드가 가득 찼습니다. 잠시 후 다시 시도해 주세요.',
  REPLACED_BY_SAME_USER: '다른 기기에서 접속해 이 연결이 종료되었습니다.',
  WORLD_SESSION_UNAVAILABLE: '월드 입장 정보를 받지 못했습니다.',
};

function disconnectReason(detail: string): string {
  return DISCONNECT_REASONS[detail] ?? '연결을 다시 세우지 못했습니다.';
}

export function UnityHost() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const instanceRef = useRef<UnityInstance | null>(null);
  const [status, setStatus] = useState<HostStatus>('booting');
  const [progress, setProgress] = useState(0);
  const [attempt, setAttempt] = useState(0);
  const [instanceReady, setInstanceReady] = useState(false);
  // 월드 로딩이 길어졌을 때 문구를 한 번 더 바꾼다 — 실패 판정이 아니다
  const [longWait, setLongWait] = useState(false);
  // Unity 가 밀어 주는 접속 상태. null = 아직 아무 신호도 오지 않았다(신호 없이도 예전과 똑같이 동작한다)
  const [connection, setConnection] = useState<{ state: WorldConnectionState; detail: string } | null>(null);
  const session = useSession();
  // 입력 소유권 판정의 유일한 입력값 — 오버레이가 하나라도 열려 있으면 월드 입력을 잠근다(-450, #132).
  // OverlayHost(렌더러)가 아니라 Overlay Bus 를 직접 본다: 잠금은 무엇이 그려지는가가 아니라
  // 무엇이 키보드를 갖는가의 문제라, 렌더 트리와 무관하게 store 하나로 판정하는 것이 맞다.
  const overlay = useSyncExternalStore(subscribeOverlay, getCurrentOverlay);

  useEffect(() => {
    initUnityBridge(); // 멱등 — 재호출해도 window.FestaUnity를 다시 잇기만 한다

    let cancelled = false;
    const canvas = canvasRef.current;
    if (!canvas) return;

    setStatus('booting');
    setProgress(0);
    setInstanceReady(false);
    setLongWait(false);
    setConnection(null);
    instanceRef.current = null;

    // boot watchdog — 진행률이 멈춘 채 UNITY_BOOT_STALL_TIMEOUT_MS 가 지나면 실패. 진행률마다 다시 재고,
    // 인스턴스가 서면 해제한다. 이 타이머는 boot attempt 의 시간이지 사용자의 페이지 체류 시간이 아니다.
    let watchdogId: ReturnType<typeof setTimeout> | null = null;
    const clearWatchdog = () => {
      if (watchdogId !== null) clearTimeout(watchdogId);
      watchdogId = null;
    };
    const armWatchdog = () => {
      clearWatchdog();
      watchdogId = setTimeout(() => {
        if (!cancelled) setStatus('failed');
      }, UNITY_BOOT_STALL_TIMEOUT_MS);
    };
    armWatchdog();

    // 첫 시도는 단순 획득, 재시도는 기존 인스턴스 종료를 기다린 뒤 새로 만든다(B-3).
    const start = attempt === 0 ? acquireUnitySession : restartUnitySession;
    start(canvas, (p) => {
      if (cancelled) return;
      setProgress(p);
      armWatchdog();
    }).then((instance) => {
      if (cancelled) return;
      clearWatchdog();
      instanceRef.current = instance;
      setInstanceReady(true);
      // 인스턴스가 섰으면 boot 는 끝이다 — 이후 로비·게이트 대기는 Unity 가 그린다. 게이트 신호가 boot 보다
      // 먼저 왔다면(mock 로더) ready 를 덮어쓰지 않는다.
      setStatus((current) => (current === 'ready' ? current : 'waiting-gate'));
    }).catch(() => {
      if (cancelled) return;
      clearWatchdog();
      setStatus('failed');
    });

    const unsubscribeGate = subscribeWorldGateReady(() => {
      if (!cancelled) setStatus('ready');
    });

    // 로비에서 월드 입장을 누른 순간. waiting-gate 에서만 받는다 — boot 중이거나 이미 ready 면 무시한다.
    const unsubscribeLoad = subscribeWorldLoadStart(() => {
      if (cancelled) return;
      setStatus((current) => (current === 'waiting-gate' ? 'preparing-world' : current));
    });

    // 접속 상태는 boot 성공 여부와 무관하게 들어올 수 있다 — 별도 구독으로 받고 여기서 판정하지 않는다.
    const unsubscribeConnection = subscribeWorldConnectionState((state, detail) => {
      if (!cancelled) setConnection({ state, detail });
    });

    return () => {
      cancelled = true;
      unsubscribeGate();
      unsubscribeLoad();
      unsubscribeConnection();
      clearWatchdog();
    };
  }, [attempt]);

  // 월드 로딩이 길어지면 문구를 바꾼다. preparing-world 에 들어간 시점부터 재고, 나가면 초기화한다.
  useEffect(() => {
    if (status !== 'preparing-world') {
      setLongWait(false);
      return;
    }
    const id = setTimeout(() => setLongWait(true), WORLD_PREPARING_LONG_WAIT_MS);
    return () => clearTimeout(id);
  }, [status]);

  // 013a-AT(-91): 인스턴스가 서면 현재 세션의 Access Token 을 Unity 에 반영하고, 세션 종류·만료(refresh)가
  // 바뀔 때마다 다시 밀어 넣는다. 입장 게이트 ready 에서도 한 번 더 — 초기 SendMessage 가 씬 로드보다 앞섰을
  // 때의 보험(멱등). 회원·게스트 모두 전달하고 비로그인만 Clear 다(#128 §2).
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    syncAccessToken(instance);
  }, [instanceReady, status, session.kind, session.expiresAt]);

  // G-8 입력 소유권 (-450, #132): 오버레이 개폐를 Unity 잠금에 반영한다. 인스턴스가 선 직후에도 한 번 —
  // 재시도 boot 로 새 인스턴스가 서면 그 인스턴스는 잠금 상태를 모르기 때문이다(상태는 인스턴스마다 새로 시작).
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    syncInputLock(instance, overlay !== null);
  }, [instanceReady, overlay]);

  // 최초 월드 진입 시 캔버스에 focus 를 준다 (-450, #132). !279 로 captureAllKeyboardInput=false 가 되면서
  // canvas 에 focus 가 없으면 WASD·F 가 Unity 에 들어가지 않는다 — 진입 직후 activeElement 가 SECTION 이라
  // 첫 키가 무시되는 것을 게임 파트가 실측했다(2026-09-05 23:00). tabIndex=-1 이라 프로그램 focus 가 된다(-421).
  // 오버레이가 열려 있는 채로 들어왔다면 뺏지 않는다 — 그쪽이 키보드 주인이다.
  useEffect(() => {
    if (!instanceReady || overlay !== null) return;
    canvasRef.current?.focus();
    // overlay 를 의존성에 넣지 않는다: 오버레이를 닫을 때의 focus 복구는 -428(OverlayFrame)이 이미 하고 있고,
    // 여기서 또 하면 두 곳이 같은 일을 다투게 된다. 이 효과는 boot attempt 당 1회다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instanceReady]);

  // 진짜 언마운트에서만 세션을 정리한다 — sessionManager의 예약 지연이 StrictMode의
  // mount→cleanup→mount 사이에서 다음 마운트의 acquire 호출로 취소된다(B-2).
  useEffect(() => {
    return () => {
      releaseUnitySession();
    };
  }, []);

  function handleRetry() {
    setAttempt((n) => n + 1);
  }

  // 'disconnected' 를 곧바로 실패로 읽지 않는다 — Unity 가 곧 재시도에 들어가므로 같은 "재접속 중" 구간이다.
  // 사용자가 직접 끊은 경우(detail 'USER')만 예외로, 되돌릴 것이 없으니 아무 안내도 띄우지 않는다.
  const reconnecting =
    connection?.state === 'reconnecting' ||
    (connection?.state === 'disconnected' && connection.detail !== 'USER');
  // 접속 안내가 떠 있는 동안에는 boot 계열 안내를 겹쳐 띄우지 않는다
  const connectionNotice = reconnecting || connection?.state === 'failed';

  return (
    // 상태 층이 캔버스 위에 겹치려면 이 컨테이너가 위치 기준이어야 한다(.uh-status 는 absolute inset:0)
    <div className="uh-root">
      {/* id·tabIndex 는 Unity 기본 템플릿(unity-canvas)과 같게 둔다 — Unity 6 WebGL 은 키보드 이벤트
          타깃을 `"#" + canvas.id` 선택자로 찾아서, id 가 없으면 `querySelector("#")` SyntaxError 로
          _main 직후 죽는다(2026-09-05 통합 실측에서 확인). tabIndex=-1 은 캔버스가 키 입력을 받게 한다. */}
      <canvas id="unity-canvas" tabIndex={-1} ref={canvasRef} style={{ width: '100%', height: '100%' }} />
      {reconnecting && (
        <div className="uh-status" role="status" aria-live="polite">
          <span className="uh-status-spinner" aria-hidden="true" />
          <strong className="uh-status-title">재접속 중…</strong>
          <span className="uh-status-hint">연결이 끊어져 다시 연결하고 있습니다.</span>
        </div>
      )}
      {connection?.state === 'failed' && (
        <div className="uh-status" role="alert">
          <strong className="uh-status-title">월드 연결이 끊어졌습니다</strong>
          <span className="uh-status-hint">{disconnectReason(connection.detail)}</span>
          <button type="button" onClick={handleRetry}>
            로비로 돌아가기
          </button>
        </div>
      )}
      {!connectionNotice && status === 'booting' && (
        <div className="uh-status" role="status" aria-live="polite">
          <span className="uh-status-spinner" aria-hidden="true" />
          <strong className="uh-status-title">게임을 준비하고 있어요</strong>
          {/* boot 구간은 진짜 진행률이 있다 — 여기서만 숫자를 보여준다 */}
          <span className="uh-status-progress">{Math.round(progress * 100)}%</span>
        </div>
      )}
      {!connectionNotice && status === 'preparing-world' && (
        <div className="uh-status" role="status" aria-live="polite">
          {/* 진행률 신호가 없는 구간이다 — indeterminate 로 두고 가짜 백분율을 만들지 않는다 */}
          <span className="uh-status-spinner" aria-hidden="true" />
          <strong className="uh-status-title">
            {longWait ? '월드를 준비하고 있습니다' : '축제장을 불러오고 있어요'}
          </strong>
          {longWait && <span className="uh-status-hint">잠시만 기다려 주세요.</span>}
        </div>
      )}
      {status === 'failed' && (
        <div>
          <p>월드를 불러오지 못했습니다.</p>
          <button type="button" onClick={handleRetry}>
            다시 시도
          </button>
        </div>
      )}
    </div>
  );
}
