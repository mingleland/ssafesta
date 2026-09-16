// /app/world 가 마운트하는 화면 — 로그인 후 사용자가 상주하는 기본 상태다(D-08).
// Unity WebGL Host(013a) + Overlay 렌더러 + Interaction Dispatcher 를 조립한다(016 E2E).
//
// 화면 계층은 서로 분리돼 있다:
//   WorldSurface   Unity(또는 mock 정지 화면)
//   WorldHud       허용 HUD 만 — 조작 안내 · 상담 Quick Access
//   OverlayHost    Unity 상호작용이 여는 Visitor Overlay (Overlay Bus)
//   GameMenu       사용자가 ESC 로 여는 개인/시스템 레이어 (features/world/model/gameClientUi)
//
// 셋 중 무엇이 주인인지는 features/world/model/worldScreen 이 판정한다 — 배타·ESC·Unity 입력
// 잠금이 모두 그 한 곳을 쓴다. 이 화면은 판정하지 않고 렌더와 생명주기만 맡는다.
//
// Dispatcher 구독은 이 화면 생명주기에 종속시킨다 — 전역 상시 구독이면 월드 밖에서도 Unity
// 이벤트가 오버레이를 열 수 있고 StrictMode에서 leak된다.
import { useCallback, useEffect, useState, type CSSProperties } from 'react';
import { useSearchParams } from 'react-router-dom';
import { IS_MOCK_WORLD } from '../../features/world/ui/WorldSurface.select';
import { useHostPhase } from '../../unity/host/hostPhase';
import { hideWorld, showWorld } from '../../unity/host/worldMount';
import { WorldHud } from '../../features/world/ui/WorldHud';
import { MockInteractionBar } from '../../features/world/ui/MockInteractionBar';
import { GameMenu } from '../../features/world/ui/GameMenu';
import { MyInfoOverlay } from '../../features/profile/ui/MyInfoOverlay';
import { BoothManagementOverlay } from '../../features/booth/ui/BoothManagementOverlay';
import { ManagementPanelHost } from '../../features/booth/ui/ManagementPanelHost';
import { OverlayHost } from '../../features/overlay/OverlayHost';
import { initInteractionDispatcher } from '../../features/interaction/dispatcher';
import { startBoothVisitTracking } from '../../features/world/model/boothVisitTracker';
import { startBoothVisitReporting } from '../../features/world/model/boothVisitReporter';
import { WorldChatLayer } from '../../features/worldChat/ui/WorldChatLayer';
import {
  WORLD_CHAT_INPUT_ID,
  canUseWorldChat,
  closeWorldChat,
  getWorldChatSnapshot,
  openWorldChat,
  resolveEnterAction,
  sendWorldChat,
  startWorldChat,
} from '../../features/worldChat/model/worldChat';
import { closeOverlay } from '../../shared/types/overlay';
import {
  IS_DEV_INTERACTION_BAR,
  closeBoothManagement,
  closeManagementPanel,
  closeGameMenu,
  closeMyInfo,
  resetGameClientUi,
  useGameClientUi,
} from '../../features/world/model/gameClientUi';
import { closeTopScreen, getWorldScreen, openManagement, openMenu, openMyInfoScreen } from '../../features/world/model/worldScreen';
import { hasUnityModal, resetWorldUiState } from '../../unity/bridge/worldUiState';
import { getReadyUnityInstance } from '../../unity/host/sessionManager';
import { requestExitWorldUi } from '../../unity/host/worldUiBridge';
import './worldPage.css';

export function WorldPage() {
  const ui = useGameClientUi();
  // 캐릭터 선택·부팅·월드 로딩 중에는 World HUD 를 그리지 않는다 (S15P21A604-613).
  // Unity 가 로비를 그리는 동안 "W A S D 이동"·"부스 입장" 안내가 떠 있으면 사실과 다르고,
  // 상담·도움말도 그 맥락에서 열 수 있는 것이 아니다.
  // mock 월드는 UnityHost 자체가 뜨지 않아 단계가 booting 에 머무르므로 예외로 둔다.
  // 훅은 항상 부른다 — `IS_MOCK_WORLD ||` 뒤에 두면 단축 평가로 호출이 건너뛰어진다
  const hostPhase = useHostPhase();
  const inWorld = IS_MOCK_WORLD || hostPhase === 'ready';
  const [params, setParams] = useSearchParams();
  const [chatHeight, setChatHeight] = useState(0);
  const reportChatHeight = useCallback((height: number) => {
    setChatHeight((current) => current === height ? current : height);
  }, []);

  // 월드를 보여 달라고 요청한다. 화면을 떠날 때는 감추기만 하고 내리지 않는다 —
  // 부스 스튜디오·관리 상세로 나갔다 돌아오는 것이 정상 동선이고, 그때마다 다시 부팅하지
  // 않는 것이 상주 호스트의 존재 이유다 (S15P21A604-620).
  useEffect(() => {
    showWorld();
    return hideWorld;
  }, []);

  // ToastHost 는 라우터 밖에 있으므로, 월드에서만 쓰는 상단 중앙 예약 영역은 body 상태로 연결한다.
  useEffect(() => {
    document.body.classList.add('world-active');
    return () => document.body.classList.remove('world-active');
  }, []);

  // Booth Studio·관리 상세에서 돌아왔다면(?panel=management) 관리 화면을 그 자리에 복원한다.
  // 모듈 상태의 "복귀 예약"이 아니라 URL 로 표현한다 — StrictMode 재mount 와 새로고침 양쪽에서
  // 같은 결과가 나오는 유일한 방법이다.
  useEffect(() => {
    if (params.get('panel') !== 'management') return;
    openManagement();
    // 한 번 열고 나면 쿼리는 지운다 — 이후 새로고침이 같은 화면을 강제로 다시 열지 않게
    setParams({}, { replace: true });
  }, [params, setParams]);

  useEffect(() => {
    const unsubscribe = initInteractionDispatcher();
    // 부스 방문 경계 추적 — dispatcher 가 WORLD_BOOTH_CONTEXT 를 나르므로 수명을 같이 둔다
    // (S15P21A604-690). 경계를 잡는 쪽과 서버로 보내는 쪽이 갈려 있고, 둘 다 이 화면이 사는
    // 동안만 산다 — 월드를 떠난 뒤 늦게 도착한 전이가 요청을 만들지 않게.
    const stopVisitTracking = startBoothVisitTracking();
    const stopVisitReporting = startBoothVisitReporting();
    return () => {
      unsubscribe();
      stopVisitReporting();
      stopVisitTracking();
      // Overlay Bus·클라이언트 UI 는 module-level 상태라 이 화면이 unmount 돼도 남는다 —
      // 벗어날 때 명시적으로 닫아 재진입 시 과거 레이어가 즉시 떠 있지 않게 한다.
      closeOverlay();
      resetGameClientUi();
    };
  }, []);

  // ESC 중재 — **이 화면이 유일한 판정자다** (-450, #132).
  //
  //   1. FE 레이어가 있으면  → 그 하나만 닫는다. Unity 모달은 유지한다
  //   2. FE 레이어가 없고 Unity 모달이 있으면 → 닫아 달라고 요청하고 Game Menu 는 열지 않는다
  //   3. 둘 다 없으면 → Game Menu ("ESC = 나/시스템", D-08)
  //
  // 2단계가 이번에 생겼다. 전에는 FE 레이어가 없으면 곧장 Game Menu 를 열었는데, 그 판정의 입력값
  // (worldScreen)은 FE store 둘만 본다 — Unity 가 초점 카메라나 미니게임 HUD 를 쥐고 있어도 FE 에게는
  // 'world' 로 보였다. 그래서 줌만 켜진 상태에서 ESC 를 누르면 **줌은 풀리는데 설정 창이 같이 떴다.**
  // 이제 Unity 관측값(bridge/worldUiState)을 함께 본다.
  //
  // **상태를 보고 남의 것을 닫지 않는다.** focus 가 true 라고 FE 가 Unity 를 끄는 것이 아니라
  // 요청을 보내고, 무엇을 닫을지는 Unity 가 정한다(worldUiBridge.ts 주석 — 2026-09-08 슬롯머신 사고).
  //
  // 다른 ESC 리스너는 걷어냈다. OverlayFrame·GameOverlay 도 window 에 걸려 있었는데, 같은 target
  // 이라 stopPropagation 으로는 서로를 막을 수 없고 등록 순서로만 갈렸다 — 그래서 GAME 오버레이는
  // capture 로 먼저 닫히고 뒤이어 이 핸들러가 "떠 있는 게 없다" 로 읽어 Game Menu 를 열고 있었다.
  // 판정자를 하나로 두는 것이 그 계열을 통째로 없애는 방법이다. focus 복구(-428)는 OverlayFrame 의
  // 언마운트 효과가 그대로 하므로 영향이 없다.
  //
  // ESC 한 번이 여러 레이어를 동시에 걷지 않는다(worldPageEscLayering.test.tsx).
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;
      // 네이티브 <dialog> 는 ESC 를 자기가 소비해 닫지만 keydown 은 window 까지 올라온다.
      // 그대로 두면 확인 모달이 닫히면서 그 아래 레이어까지 같이 닫힌다 — 리스너를 새로 만들지
      // 않고 판정자 맨 앞에서 한 번 비켜 준다. 텍스트 입력 중 ESC 도 같은 경로로 dialog 가 먼저
      // 먹는다.
      if (document.querySelector('dialog[open]') !== null) return;
      // 채팅이 열려 있으면 그것부터 닫는다. 입력창을 두고 Game Menu 가 열리면 글을 쓰다 말고
      // 메뉴가 덮는다.
      if (getWorldChatSnapshot().open) {
        closeWorldChat();
        return;
      }
      if (closeTopScreen()) return;

      if (hasUnityModal()) {
        const instance = getReadyUnityInstance();
        if (instance !== null) {
          requestExitWorldUi(instance, 'esc');
          return;
        }
        // 관측값은 남아 있는데 인스턴스가 없다(mock 월드·종료 직후). 보낼 곳이 없으므로 관측값을
        // 신뢰하지 않고 비운 뒤 3단계로 내려간다 — ESC 가 아무 데도 가지 않는 상태를 만들지 않는다.
        resetWorldUiState();
      }

      openMenu();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  // Enter 판정자도 한 곳이다 — ESC 와 같은 이유다. 채팅 화면이 자기 리스너를 걸면 등록 순서로만
  // 갈리는 그 계열의 버그가 돌아온다 (S15P21A604-706).
  useEffect(() => {
    const stopChat = startWorldChat();

    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Enter') return;
      // 다른 텍스트 입력(AI 직원 채팅 등)이 focus 를 쥔 동안은 그 입력의 네이티브 form
      // submit 에 맡긴다 — World Chat 의 Enter 판정자는 자기 입력창 밖의 텍스트 입력까지
      // 가로챌 권한이 없다 (S15P21A604-823).
      const active = document.activeElement;
      const otherInputFocused =
        active instanceof HTMLElement &&
        (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA') &&
        active.id !== WORLD_CHAT_INPUT_ID;
      if (otherInputFocused) return;
      // 조합 중 Enter·Shift+Enter 를 거르는 규칙까지 resolveEnterAction 이 갖는다 — 규칙을 두
      // 곳에 두면 갈린다.
      const chat = getWorldChatSnapshot();
      const action = resolveEnterAction(e, {
        open: chat.open,
        inputFocused: document.activeElement?.id === WORLD_CHAT_INPUT_ID,
        member: canUseWorldChat(),
      });
      if (action === 'ignore') return;
      e.preventDefault();
      if (action === 'send') sendWorldChat(chat.draft);
      // 패널은 떠 있는데 입력창이 focus 를 잃은 상태 — 새 창을 열지 않고 그 입력창으로 돌아간다
      else if (action === 'focus') document.getElementById(WORLD_CHAT_INPUT_ID)?.focus();
      else openWorldChat();
    }

    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      stopChat();
    };
  }, []);

  // Tab 잠금 (S15P21A604-450) — 브라우저 기본 동작은 Tab 에서 다음 포커스 가능 요소로 옮긴다.
  // Unity 6 WebGL 은 키보드 타깃을 canvas 로 잡으므로(captureAllKeyboardInput=false, !279),
  // 포커스가 캔버스를 벗어나면 그 뒤 Tab keydown 이 Unity 에 안 들어가 자체 미니맵 토글이
  // 죽는다. 실제 canvas가 키보드 타깃일 때만 막는다. HUD·오버레이 등 DOM 요소에서는
  // 접근성을 위해 브라우저의 Tab 탐색을 그대로 둔다.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Tab') return;
      if (getWorldScreen() !== 'world') return;
      if (e.target !== document.getElementById('unity-canvas')) return;
      e.preventDefault();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  return (
    <div className="world-scene" style={{ '--festa-world-chat-height': `${chatHeight}px` } as CSSProperties}>
      {/* World Layer 는 이 트리에 없다 — 라우트 밖 PersistentWorld 가 그린다 (S15P21A604-620).
          여기서 그리면 화면을 떠날 때 Unity 가 함께 죽어 돌아올 때마다 50~84초를 다시 기다린다. */}
      {/* React HUD — hud-decisions 가 허용한 것만 (조작 안내 · 상담 Quick Access) */}
      {inWorld && <WorldHud />}
      {/* 채팅 — HUD 밖이다. HUD 의 mousedown 차단이 입력창 focus 를 막는다 (S15P21A604-648) */}
      {inWorld && <WorldChatLayer onHeightChange={reportChatHeight} />}
      {/* DEV_ONLY — 제품 HUD 가 아니다. dev 빌드 + VITE_DEV_INTERACTION_BAR=true 에서만 뜬다 */}
      {IS_DEV_INTERACTION_BAR && <MockInteractionBar />}
      {/* Visitor Overlay Layer — Unity 상호작용이 연다 */}
      <OverlayHost />
      {/* Booth Management Layer — World 의 관리 NPC 가 연다(계약 G-1 전까지 dev trigger) */}
      {ui.managementOverlay && <BoothManagementOverlay onClose={closeBoothManagement} />}
      {/* 관리 상세는 관리 화면의 자식이다 — 위에 얹히고, 닫으면 관리 화면이 다시 드러난다 */}
      {ui.managementPanel !== null && (
        <ManagementPanelHost panel={ui.managementPanel} onClose={closeManagementPanel} />
      )}
      {/* Personal / System Layer — 사용자가 ESC 로 연다 */}
      {ui.gameMenu && <GameMenu onClose={closeGameMenu} onOpenMyInfo={openMyInfoScreen} />}
      {/* 내 정보 — GameMenu 의 "내 정보"가 연다. 오버레이라 월드 위에 뜬다(페이지 이동 아님) */}
      {ui.myInfo && <MyInfoOverlay onClose={closeMyInfo} />}
    </div>
  );
}
