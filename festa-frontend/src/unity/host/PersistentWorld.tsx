// 라우트보다 오래 사는 Unity 월드 (S15P21A604-620).
//
// `RouterProvider` 와 형제로 선다. 라우트 트리 밖이라 화면이 바뀌어도 언마운트되지 않고,
// 그래서 Unity 인스턴스가 살아남는다 — 이 컴포넌트의 존재 이유가 그것 하나다.
//
// **WorldPage 를 복제하지 않는다.** 여기 있는 것은 `WorldSurface` 하나뿐이고 HUD·오버레이·
// ESC 중재는 전부 `WorldPage` 에 그대로 남는다. 그쪽이 라우트에 묶여 있어야 맞다 — 월드
// 화면을 떠났는데 조작 안내가 따라다니면 안 된다.
//
// `WorldSurface.select` seam 을 그 자리에서 승격한 것이다. 그 파일 주석이 예고한 지점이고,
// mock 월드도 같은 seam 을 타므로 실행 모드가 갈리지 않는다.
import { useEffect, useSyncExternalStore } from 'react';
import { WorldSurface } from '../../features/world/ui/WorldSurface.select';
import { useSession } from '../../features/auth/model/session';
import { getWorldMount, subscribeWorldMount, unmountWorld } from './worldMount';
import './persistentWorld.css';

export function PersistentWorld() {
  const mount = useSyncExternalStore(subscribeWorldMount, getWorldMount, getWorldMount);
  const { kind, bootstrapped } = useSession();

  // 세션이 사라지면 내린다 — 로그아웃이든 만료든 돌아올 월드가 없다. 판정을 여기 한 곳에
  // 두는 이유는 두 가지다: 로그아웃·가드 redirect 두 자리에 같은 호출을 흩지 않고, 렌더 중에
  // 다른 컴포넌트의 store 를 갱신하지 않기 위해서다(가드는 렌더 phase 에서 redirect 한다).
  // 게스트는 내리지 않는다 — 월드는 guest-allowed 다.
  useEffect(() => {
    if (bootstrapped && kind === 'anonymous') unmountWorld();
  }, [bootstrapped, kind]);

  // 아직 월드에 들어간 적이 없으면 아무것도 만들지 않는다 — 로그인 화면에서 WebGL 을 띄울
  // 이유가 없다. 부팅 시점은 예전과 같이 첫 월드 진입이다.
  if (!mount.mounted) return null;

  // 감출 때도 DOM 에서 떼지 않는다. canvas 가 사라지면 Unity 가 그릴 곳을 잃는다 —
  // 이 컴포넌트가 막으려는 것이 정확히 그 파괴다.
  return (
    <div className="persistent-world" data-visible={mount.visible} aria-hidden={!mount.visible}>
      <WorldSurface />
    </div>
  );
}
