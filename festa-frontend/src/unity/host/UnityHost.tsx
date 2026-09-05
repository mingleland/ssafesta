// spec 013a WebGL Host — Unity 인스턴스를 안전하게 생성·종료하고 입장 게이트 신호(#31)·
// boot watchdog(-426)까지만 책임진다. 오버레이 렌더러(OverlayHost)·Interaction Dispatcher·016 E2E는
// 범위 밖 — 이 컴포넌트는 그 위에서 동작할 기반이다.
//
// 상태 수명주기 (#128 §3): booting(watchdog 감시) → instance 확보 → waiting-gate(타임아웃 없음 — 로비 체류·
// 입장 게이트 대기는 사용자 시간이다) → ready. 실패는 boot 구간에서만 판정한다. 재시도는 새 boot attempt 로
// 새 watchdog 을 건다.
import { useEffect, useRef, useState } from 'react';
import { initUnityBridge, subscribeWorldGateReady } from '../bridge/events';
import { acquireUnitySession, releaseUnitySession, restartUnitySession } from './sessionManager';
import { syncAccessToken } from './authBridge';
import { useSession } from '../../features/auth/model/session';
import { UNITY_BOOT_STALL_TIMEOUT_MS } from '../../shared/config/unity';
import type { UnityInstance } from './types';

type HostStatus = 'booting' | 'waiting-gate' | 'ready' | 'failed';

export function UnityHost() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const instanceRef = useRef<UnityInstance | null>(null);
  const [status, setStatus] = useState<HostStatus>('booting');
  const [progress, setProgress] = useState(0);
  const [attempt, setAttempt] = useState(0);
  const [instanceReady, setInstanceReady] = useState(false);
  const session = useSession();

  useEffect(() => {
    initUnityBridge(); // 멱등 — 재호출해도 window.FestaUnity를 다시 잇기만 한다

    let cancelled = false;
    const canvas = canvasRef.current;
    if (!canvas) return;

    setStatus('booting');
    setProgress(0);
    setInstanceReady(false);
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

    const unsubscribe = subscribeWorldGateReady(() => {
      if (!cancelled) setStatus('ready');
    });

    return () => {
      cancelled = true;
      unsubscribe();
      clearWatchdog();
    };
  }, [attempt]);

  // 013a-AT(-91): 인스턴스가 서면 현재 세션의 Access Token 을 Unity 에 반영하고, 세션 종류·만료(refresh)가
  // 바뀔 때마다 다시 밀어 넣는다. 입장 게이트 ready 에서도 한 번 더 — 초기 SendMessage 가 씬 로드보다 앞섰을
  // 때의 보험(멱등). 회원·게스트 모두 전달하고 비로그인만 Clear 다(#128 §2).
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    syncAccessToken(instance);
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
      {status === 'booting' && <p>불러오는 중... {Math.round(progress * 100)}%</p>}
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
