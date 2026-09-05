// spec 013a WebGL Host — Unity 인스턴스를 안전하게 생성·종료하고 입장 게이트 신호(#31)·
// 60초 타임아웃까지만 책임진다. 오버레이 렌더러(OverlayHost)·Interaction Dispatcher·016 E2E는
// 범위 밖 — 이 컴포넌트는 그 위에서 동작할 기반이다.
import { useEffect, useRef, useState } from 'react';
import { initUnityBridge, subscribeWorldGateReady } from '../bridge/events';
import { acquireUnitySession, releaseUnitySession, restartUnitySession } from './sessionManager';
import { syncAccessToken } from './authBridge';
import { useSession } from '../../features/auth/model/session';
import { WORLD_GATE_TIMEOUT_MS } from '../../shared/config/unity';
import type { UnityInstance } from './types';

type HostStatus = 'loading' | 'ready' | 'failed';

export function UnityHost() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const instanceRef = useRef<UnityInstance | null>(null);
  const [status, setStatus] = useState<HostStatus>('loading');
  const [progress, setProgress] = useState(0);
  const [attempt, setAttempt] = useState(0);
  const [instanceReady, setInstanceReady] = useState(false);
  const session = useSession();

  useEffect(() => {
    initUnityBridge(); // 멱등 — 재호출해도 window.FestaUnity를 다시 잇기만 한다

    let cancelled = false;
    const canvas = canvasRef.current;
    if (!canvas) return;

    setStatus('loading');
    setProgress(0);
    setInstanceReady(false);
    instanceRef.current = null;

    // 첫 시도는 단순 획득, 재시도는 기존 인스턴스 종료를 기다린 뒤 새로 만든다(B-3).
    const start = attempt === 0 ? acquireUnitySession : restartUnitySession;
    start(canvas, (p) => {
      if (!cancelled) setProgress(p);
    }).then((instance) => {
      if (cancelled) return;
      instanceRef.current = instance;
      setInstanceReady(true);
    }).catch(() => {
      if (!cancelled) setStatus('failed');
    });

    const unsubscribe = subscribeWorldGateReady(() => {
      if (!cancelled) setStatus('ready');
    });

    const timeoutId = setTimeout(() => {
      if (!cancelled) setStatus((current) => (current === 'ready' ? current : 'failed'));
    }, WORLD_GATE_TIMEOUT_MS);

    return () => {
      cancelled = true;
      unsubscribe();
      clearTimeout(timeoutId);
    };
  }, [attempt]);

  // 013a-AT(-91): 인스턴스가 서면 현재 세션을 Unity 에 반영하고, 세션 종류·만료(refresh)가 바뀔 때마다
  // 다시 밀어 넣는다. 입장 게이트 ready 에서도 한 번 더 — 초기 SendMessage 가 씬 로드보다 앞섰을 때의
  // 보험(멱등). 게스트는 Clear 라 토큰이 노출되지 않는다.
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    syncAccessToken(instance, session);
  }, [instanceReady, status, session.kind, session.expiresAt]);

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

  return (
    <div>
      {/* id·tabIndex 는 Unity 기본 템플릿(unity-canvas)과 같게 둔다 — Unity 6 WebGL 은 키보드 이벤트
          타깃을 `"#" + canvas.id` 선택자로 찾아서, id 가 없으면 `querySelector("#")` SyntaxError 로
          _main 직후 죽는다(2026-09-05 통합 실측에서 확인). tabIndex=-1 은 캔버스가 키 입력을 받게 한다. */}
      <canvas id="unity-canvas" tabIndex={-1} ref={canvasRef} style={{ width: '100%', height: '100%' }} />
      {status === 'loading' && <p>불러오는 중... {Math.round(progress * 100)}%</p>}
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
