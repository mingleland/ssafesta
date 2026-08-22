// /app/world가 마운트하는 화면 — Unity WebGL Host(013a) + Overlay 렌더러 + Interaction
// Dispatcher를 조립한다(016 E2E). Dispatcher 구독은 이 화면 생명주기에 종속시킨다 — 전역
// 상시 구독이면 월드 밖에서도 Unity 이벤트가 오버레이를 열 수 있고 StrictMode에서 leak된다.
import { useEffect } from 'react';
import { UnityHost } from '../../unity/host/UnityHost';
import { OverlayHost } from '../../features/overlay/OverlayHost';
import { initInteractionDispatcher } from '../../features/interaction/dispatcher';
import { closeOverlay } from '../../shared/types/overlay';

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
    <>
      <UnityHost />
      <OverlayHost />
    </>
  );
}
