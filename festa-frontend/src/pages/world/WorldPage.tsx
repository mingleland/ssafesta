// /app/world 가 마운트하는 화면 — 로그인 후 사용자가 상주하는 기본 상태다(D-08).
// Unity WebGL Host(013a) + Overlay 렌더러 + Interaction Dispatcher 를 조립한다(016 E2E).
//
// 화면 계층은 서로 분리돼 있다:
//   WorldSurface   Unity(또는 mock 정지 화면)
//   WorldHud       허용 HUD 만 — 조작 안내 · 상담 Quick Access
//   OverlayHost    Unity 상호작용이 여는 Visitor Overlay (Overlay Bus)
//   GameMenu       사용자가 ESC 로 여는 개인/시스템 레이어 (features/world/model/gameClientUi)
//
// Dispatcher 구독은 이 화면 생명주기에 종속시킨다 — 전역 상시 구독이면 월드 밖에서도 Unity
// 이벤트가 오버레이를 열 수 있고 StrictMode에서 leak된다.
import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { IS_MOCK_WORLD, WorldSurface } from '../../features/world/ui/WorldSurface.select';
import { WorldHud } from '../../features/world/ui/WorldHud';
import { MockInteractionBar } from '../../features/world/ui/MockInteractionBar';
import { GameMenu } from '../../features/world/ui/GameMenu';
import { BoothManagementOverlay } from '../../features/booth/ui/BoothManagementOverlay';
import { OverlayHost } from '../../features/overlay/OverlayHost';
import { initInteractionDispatcher } from '../../features/interaction/dispatcher';
import { closeOverlay, getCurrentOverlay } from '../../shared/types/overlay';
import {
  IS_DEV_INTERACTION_BAR,
  closeBoothManagement,
  closeGameMenu,
  getGameClientUiSnapshot,
  openBoothManagement,
  openGameMenu,
  resetGameClientUi,
  useGameClientUi,
} from '../../features/world/model/gameClientUi';
import './worldPage.css';

export function WorldPage() {
  const ui = useGameClientUi();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  // Booth Studio·관리 상세에서 돌아왔다면(?panel=management) 관리 화면을 그 자리에 복원한다.
  // 모듈 상태의 "복귀 예약"이 아니라 URL 로 표현한다 — StrictMode 재mount 와 새로고침 양쪽에서
  // 같은 결과가 나오는 유일한 방법이다.
  useEffect(() => {
    if (params.get('panel') !== 'management') return;
    openBoothManagement();
    // 한 번 열고 나면 쿼리는 지운다 — 이후 새로고침이 같은 화면을 강제로 다시 열지 않게
    setParams({}, { replace: true });
  }, [params, setParams]);

  useEffect(() => {
    const unsubscribe = initInteractionDispatcher();
    return () => {
      unsubscribe();
      // Overlay Bus·클라이언트 UI 는 module-level 상태라 이 화면이 unmount 돼도 남는다 —
      // 벗어날 때 명시적으로 닫아 재진입 시 과거 레이어가 즉시 떠 있지 않게 한다.
      closeOverlay();
      resetGameClientUi();
    };
  }, []);

  // ESC 계층: 열린 Visitor Overlay 가 있으면 그쪽이 먼저 닫는다(OverlayFrame 자체 핸들러).
  // 아무 레이어도 없을 때만 Game Menu 를 연다 — 그것이 "ESC = 나/시스템"의 의미다(D-08).
  // 장기 Input Router(game-client-experience-draft §4)는 여기서 구현하지 않는다.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;
      const { gameMenu, managementOverlay } = getGameClientUiSnapshot();
      if (gameMenu) {
        closeGameMenu();
        return;
      }
      if (managementOverlay || getCurrentOverlay() !== null) return; // 그 레이어가 자기 Esc 를 처리한다
      openGameMenu();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  return (
    <div className="world-scene">
      {/* World Layer — 교체 경계. 목업은 최신 Unity 캡처 정지 화면, 실제는 UnityHost */}
      <WorldSurface />
      {/* React HUD — hud-decisions 가 허용한 것만 (조작 안내 · 상담 Quick Access) */}
      <WorldHud mock={IS_MOCK_WORLD} />
      {/* DEV_ONLY — 제품 HUD 가 아니다. dev 빌드 + VITE_DEV_INTERACTION_BAR=true 에서만 뜬다 */}
      {IS_DEV_INTERACTION_BAR && <MockInteractionBar />}
      {/* Visitor Overlay Layer — Unity 상호작용이 연다 */}
      <OverlayHost />
      {/* Booth Management Layer — World 의 관리 NPC 가 연다(계약 G-1 전까지 dev trigger) */}
      {ui.managementOverlay && <BoothManagementOverlay onClose={closeBoothManagement} />}
      {/* Personal / System Layer — 사용자가 ESC 로 연다 */}
      {ui.gameMenu && (
        <GameMenu
          onClose={closeGameMenu}
          onOpenMyInfo={() => {
            closeGameMenu();
            navigate('/app/profile');
          }}
        />
      )}
    </div>
  );
}
