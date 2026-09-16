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
import { hasUnityModal, resetWorldUiState } from '../bridge/worldUiState';
import { resetWorldContext } from '../../features/world/model/worldContext';
import { discardPendingVisit } from '../../features/world/model/boothVisitTracker';
import { acquireUnitySession, releaseUnitySession, restartUnitySession } from './sessionManager';
import { syncAccessToken } from './authBridge';
import { syncPendingNickname } from './profileBridge';
import { syncInputLock } from './inputBridge';
import { requestExitWorldUi } from './worldUiBridge';
import { syncAudioMute, syncAudioVolume } from './audioBridge';
import { watchDevicePixelRatio } from './loader';
import { getScreenAudioSnapshot, subscribeScreenAudio } from '../../features/audio/model/screenAudio';
import { useWorldScreen } from '../../features/world/model/worldScreen';
import { useSession } from '../../features/auth/model/session';
import { UNITY_BOOT_STALL_TIMEOUT_MS, WORLD_PREPARING_LONG_WAIT_MS } from '../../shared/config/unity';
import { setHostPhase } from './hostPhase';
import { consumeFullscreenIntent, enterFullscreen } from '../../shared/ui/fullscreen';
import { getWorldMount, subscribeWorldMount } from './worldMount';
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
  // 입력 소유권 판정의 유일한 입력값 — 월드가 주인이 아닌 동안에는 월드 입력을 잠근다(-450, #132).
  // Visitor Overlay 뿐 아니라 Game Menu·Booth Management 도 포함한다: 잠금은 무엇이 그려지는가가
  // 아니라 무엇이 키보드를 갖는가의 문제이고, 그 판정은 worldScreen 하나가 갖는다.
  const screen = useWorldScreen();
  // #151(-557): 화면 음소거 선호. `useScreenAudio()` 로 상태 전체를 구독하면 재생·자동재생 대기·
  // 이관 완료 같은 무관한 전이에도 이 호스트가 다시 그려진다 — 여기서 필요한 것은 muted 하나다.
  // 좁히는 데 새 API 를 만들지 않는다: screenAudio 가 이미 내보내는 구독·스냅샷 둘로 충분하다.
  const screenMuted = useSyncExternalStore(
    subscribeScreenAudio,
    () => getScreenAudioSnapshot().muted,
    () => getScreenAudioSnapshot().muted,
  );
  // 볼륨도 같은 이유로 값 하나만 좁게 구독한다 (S15P21A604-733).
  const screenVolume = useSyncExternalStore(
    subscribeScreenAudio,
    () => getScreenAudioSnapshot().volume,
    () => getScreenAudioSnapshot().volume,
  );
  // 상주 월드가 지금 보이는가 (S15P21A604-643). muted 와 같은 이유로 visible 하나만 좁게 구독한다.
  const worldVisible = useSyncExternalStore(
    subscribeWorldMount,
    () => getWorldMount().visible,
    () => getWorldMount().visible,
  );

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
    // 새 인스턴스에는 모달이 없다 — 재시도 boot 뒤에도 옛 관측값이 남으면 ESC 가 닫을 수 없는
    // Unity 모달을 향해 명령만 보낸다(-450). 상태는 인스턴스마다 새로 시작한다.
    resetWorldUiState();
    // 새 인스턴스는 부스 안에 있지 않다 — 옛 관측값이 남으면 밖인데도 나가기 버튼이
    // 유령으로 떠 있게 된다 (S15P21A604-627).
    // 방문 경계도 함께 버린다. 순서가 중요하다 — 먼저 버려야 뒤이은 OUTSIDE 신호가 있지도 않은
    // 퇴장으로 읽히지 않는다 (S15P21A604-690).
    discardPendingVisit();
    resetWorldContext();

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
      // 로그인 때 남겨 둔 전체화면 의도를 여기서 쓴다 (S15P21A604-733). 사용자가 방금 '월드 입장' 을
      // 누른 순간이라 제스처 창 안일 가능성이 높다. 거부돼도 진입을 막지 않는다 — 수동 토글이 정본이다.
      if (consumeFullscreenIntent()) void enterFullscreen();
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

  // 이 단계를 화면 밖(World HUD)이 읽을 수 있게 옮겨 담는다 (S15P21A604-613).
  // 단방향이라 두 값이 어긋날 수 없다 — 판정은 여전히 위 effect 한 곳에서만 일어난다.
  useEffect(() => {
    setHostPhase(status);
  }, [status]);

  // 월드 로딩이 길어지면 문구를 바꾼다. preparing-world 에 들어간 시점부터 재고, 나가면 초기화한다.
  useEffect(() => {
    if (status !== 'preparing-world') {
      setLongWait(false);
      return;
    }
    const id = setTimeout(() => setLongWait(true), WORLD_PREPARING_LONG_WAIT_MS);
    return () => clearTimeout(id);
  }, [status]);

  // 013a-AT(-91): 인스턴스가 서면 현재 세션의 Access Token 을 Unity 에 반영하고, **토큰이 바뀔 때마다**
  // 다시 밀어 넣는다. 입장 게이트 ready 에서도 한 번 더 — 초기 SendMessage 가 씬 로드보다 앞섰을
  // 때의 보험(멱등). 회원·게스트 모두 전달하고 비로그인만 Clear 다(#128 §2).
  //
  // 의존성이 `expiresAt` 이던 것을 `tokenVersion` 으로 바꿨다 (S15P21A604-828). 만료 시각이 같은
  // 갱신은 이 효과를 깨우지 못해 Unity 가 옛 토큰을 계속 들고 있었다. 서버는 refresh 마다 `sid` 를
  // 회전시키고 그 순간 옛 토큰을 즉시 폐기하므로(MemberSessionService.issue), 재주입을 한 번 놓치면
  // Unity 의 모든 호출이 401 이 된다.
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    syncAccessToken(instance);
  }, [instanceReady, status, session.kind, session.tokenVersion]);

  // 프로필에서 이름을 바꾼 뒤 Unity가 재시도 boot를 하면, 새 인스턴스에도 마지막 확정 이름을 준다.
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    syncPendingNickname(instance);
  }, [instanceReady]);

  // G-8 입력 소유권 (-450, #132): 오버레이 개폐를 Unity 잠금에 반영한다. 인스턴스가 선 직후에도 한 번 —
  // 재시도 boot 로 새 인스턴스가 서면 그 인스턴스는 잠금 상태를 모르기 때문이다(상태는 인스턴스마다 새로 시작).
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    syncInputLock(instance, screen !== 'world');
  }, [instanceReady, screen]);

  // Unity 초점과 짝이 된 레이어가 닫혔으면 Unity 모달도 함께 끝낸다 (-642, #132).
  //
  // 여는 것은 하나의 동작이다 — Unity `Interact()` 가 초점 줌과 상호작용 이벤트를 같은 동기
  // 경로에서 한다(`LaptopInteractable.cs:43-44` 외 7종). 그런데 닫는 것은 ESC·배경 클릭·X 가
  // 전부 FE 쪽에만 닿아 줌이 남았다. 남으면 `InputBridge` holders 에 "InteractionFocusCamera" 가
  // 그대로 있어 `SetInputLocked('0')` 이 `LockedChanged` 를 발화시키지 못하고(owner-set),
  // **월드 입력이 통째로 잠긴 채 끝난다** — WASD·F·재진입이 전부 죽는다.
  //
  // 합류점을 `closeOverlay()` 가 아니라 화면 소유권 전이로 잡는다. 관리 화면
  // (`WORLD_MANAGEMENT_INTERACT`)도 초점을 쓰는데 그쪽은 Overlay Bus 가 아니라 gameClientUi 라,
  // Bus 에 걸면 빠진다. `worldScreen` 은 둘을 합쳐 보는 유일한 판정이다.
  //
  // **상태를 보고 남의 것을 닫지 않는다.** 요청만 보내고 무엇을 닫을지는 Unity 가 정한다
  // (worldUiBridge.ts 주석 — 2026-09-08 슬롯머신 사고). 관측값은 구독하지 않고 여기서 한 번
  // 읽는다: 필요한 것은 화면이 바뀐 그 시점의 Unity 상태뿐이고, 구독하면 무관한 전이마다
  // 이 호스트가 다시 그려진다.
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    // 아직 짝이 되는 레이어가 떠 있다 — 오버레이 종류만 바뀐 경우를 포함한다(screen 이 안 변한다)
    if (screen === 'visitor' || screen === 'management') return;
    // 줌 없이 열린 레이어였다(이벤트 NPC·상담 Quick Access·월드 안내) — 보낼 것이 없다
    if (!hasUnityModal()) return;
    requestExitWorldUi(instance, 'overlay-closed');
  }, [instanceReady, screen]);

  // #151 음소거 승계 (-557) + 볼륨 승계 (-733): 화면에서 끄거나 줄인 채로 월드에 들어가면 Unity BGM 이
  // 그 선호를 모른 채 나던 것. 인스턴스가 선 직후 현재 값을 한 번 — 새 인스턴스는 아무 상태도 모른다
  // (재시도 boot 포함). 이후에는 값이 바뀔 때만 다시 밀어 넣는다. ready 전에 바꾼 값이 사라지지 않는
  // 이유가 이것이다: 구독한 것은 store 의 현재 값이라 ready 시점의 최종 상태가 그대로 실린다.
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    syncAudioMute(instance, screenMuted);
  }, [instanceReady, screenMuted]);

  // 볼륨은 별도 effect 다. 한 effect 에 묶으면 mute 를 끄고 켤 때마다 같은 볼륨이 다시 나간다 —
  // 멱등이라 해는 없지만 두 채널이 독립이라는 것이 배선에서도 보여야 한다.
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    syncAudioVolume(instance, screenVolume);
  }, [instanceReady, screenVolume]);

  // #143 화면 밀도 변화 반영 (-575). 렌더 해상도 상한은 부팅 때 한 번 정해지는데, 확대/축소나
  // 다른 밀도의 모니터로 창을 옮기면 그 값이 낡는다 — Unity 는 이 값을 1초 주기로 다시 읽으므로
  // 바뀔 때마다 갱신하면 재부팅 없이 따라간다(loader.ts 주석의 실측). mock 인스턴스에는 Module 이
  // 없고 그 경우 감시자는 아무 일도 하지 않는다.
  useEffect(() => {
    const instance = instanceRef.current;
    if (!instanceReady || instance === null) return;
    return watchDevicePixelRatio(instance);
  }, [instanceReady]);

  // 월드 진입 시 캔버스에 focus 를 준다 (-450, #132). !279 로 captureAllKeyboardInput=false 가 되면서
  // canvas 에 focus 가 없으면 WASD·F 가 Unity 에 들어가지 않는다 — 진입 직후 activeElement 가 SECTION 이라
  // 첫 키가 무시되는 것을 게임 파트가 실측했다(2026-09-05 23:00). tabIndex=-1 이라 프로그램 focus 가 된다(-421).
  // 월드가 주인이 아닌 채로 들어왔다면 뺏지 않는다 — 떠 있는 그쪽이 키보드 주인이다.
  //
  // **상주 월드로 돌아올 때도 준다** (S15P21A604-643). -620 이전에는 라우트 복귀가 곧 이 컴포넌트의
  // 재마운트라 위 효과가 다시 돌았는데, 지금은 마운트가 유지되므로 visible 전이를 따로 봐야 한다 —
  // 안 보면 /app/profile 에서 돌아온 뒤 캔버스를 클릭하기 전까지 WASD 가 죽어 있다(2026-09-11 실측).
  // React 입력창이 focus 를 쥐고 있으면 침범하지 않는다 — 복귀 직후엔 body 라 그 경우만 받는다.
  useEffect(() => {
    if (!instanceReady || !worldVisible || screen !== 'world') return;
    const active = document.activeElement;
    if (active !== null && active !== document.body && active !== canvasRef.current) return;
    canvasRef.current?.focus();
    // screen 을 의존성에 넣지 않는다: 오버레이를 닫을 때의 focus 복구는 -428(OverlayFrame)이 이미 하고 있고,
    // 여기서 또 하면 두 곳이 같은 일을 다투게 된다. 이 효과는 boot attempt 당 1회 + 월드가 다시 보일 때 1회다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instanceReady, worldVisible]);

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
          <button type="button" className="uh-status-action" onClick={handleRetry}>
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
        // 씬 전환 중이라 dim을 불투명하게 — 반투명이면 Unity가 아직 지우지 않은 직전 씬(커스터마이징 등)의
        // 마지막 canvas 프레임이 그 뒤로 비쳐 보인다(S15P21A604-733, 실 데모 녹화로 확인).
        <div className="uh-status uh-status--opaque" role="status" aria-live="polite">
          {/* 진행률 신호가 없는 구간이다 — indeterminate 로 두고 가짜 백분율을 만들지 않는다 */}
          <span className="uh-status-spinner" aria-hidden="true" />
          <strong className="uh-status-title">
            {longWait ? '월드를 준비하고 있습니다' : '축제장을 불러오고 있어요'}
          </strong>
          {longWait && <span className="uh-status-hint">잠시만 기다려 주세요.</span>}
        </div>
      )}
      {status === 'failed' && (
        <div className="uh-status" role="alert">
          <strong className="uh-status-title">월드를 불러오지 못했습니다.</strong>
          <button type="button" className="uh-status-action" onClick={handleRetry}>
            다시 시도
          </button>
        </div>
      )}
    </div>
  );
}
