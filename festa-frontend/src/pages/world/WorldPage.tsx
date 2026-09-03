// /app/world가 마운트하는 화면 — Unity WebGL Host(013a) + Overlay 렌더러 + Interaction
// Dispatcher를 조립한다(016 E2E). Dispatcher 구독은 이 화면 생명주기에 종속시킨다 — 전역
// 상시 구독이면 월드 밖에서도 Unity 이벤트가 오버레이를 열 수 있고 StrictMode에서 leak된다.
import { useEffect } from 'react';
import { IS_MOCK_WORLD, WorldSurface } from '../../features/world/ui/WorldSurface.select';
import { WorldHud } from '../../features/world/ui/WorldHud';
import { MockInteractionBar } from '../../features/world/ui/MockInteractionBar';
import { OverlayHost } from '../../features/overlay/OverlayHost';
import { initInteractionDispatcher } from '../../features/interaction/dispatcher';
import { closeOverlay } from '../../shared/types/overlay';
import './worldPage.css';

export function WorldPage() {
  useEffect(() => {
    const unsubscribe = initInteractionDispatcher();
    return () => {
      unsubscribe();
      // Overlay Bus는 module-level 상태라 OverlayHost가 unmount돼도 current가 남는다 —
      // 이 화면을 벗어나면 명시적으로 닫아 재진입 시 과거 오버레이가 즉시 떠 있지 않게 한다.
      closeOverlay();
    };
  }, []);

  return (
    <div className="world-scene">
      {/* World Layer — 교체 경계. 목업은 최신 Unity 캡처 정지 화면, 실제는 UnityHost */}
      <WorldSurface />
      {/* React Screen Layer — hud-decisions 가 허용한 최소 HUD 만 */}
      <WorldHud mock={IS_MOCK_WORLD} />
      {/* Unity F 상호작용 대역 — 목업 월드에서만. 실제 Unity 는 dispatcher 로 같은 openOverlay 를 부른다 */}
      {IS_MOCK_WORLD && <MockInteractionBar />}
      {/* React Overlay Layer */}
      <OverlayHost />
    </div>
  );
}
