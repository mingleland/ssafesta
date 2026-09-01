// Overlay Bus의 현재 요청을 실제 화면으로 그리는 유일한 지점. Unity 이벤트는 모른다 —
// 그건 Interaction Dispatcher 몫이다(features/interaction/dispatcher.ts). 이 파일은 렌더링만 한다.
import { lazy, Suspense, useSyncExternalStore } from 'react';
import { closeOverlay, getCurrentOverlay, subscribeOverlay } from '../../shared/types/overlay';
import { LaptopOverlay } from './LaptopOverlay';
import type { GameOverlayPayload } from '../../game-studio/host/GameOverlay';

// lazy 로 가른다 — GameOverlay 는 PublishedGameSurface·ReferenceGamePlayer 를 통해 게임 런타임 전체를
// 끌고 온다. 정적 import 하면 월드 진입에 그 전부가 실린다. 타입만 쓰는 import 는 위에서 type-only 라
// 번들에 남지 않는다.
const GameOverlay = lazy(async () => ({ default: (await import('../../game-studio/host/GameOverlay')).GameOverlay }));

function subscribe(onStoreChange: () => void): () => void {
  return subscribeOverlay(() => onStoreChange());
}

export function OverlayHost() {
  const request = useSyncExternalStore(subscribe, getCurrentOverlay);

  if (!request) return null;

  if (request.type === 'LAPTOP') {
    return <LaptopOverlay payload={request.payload as { boothId: number; objectId: string }} />;
  }

  if (request.type === 'GAME') {
    return (
      <Suspense fallback={<p>게임을 여는 중입니다.</p>}>
        <GameOverlay payload={request.payload as GameOverlayPayload} />
      </Suspense>
    );
  }

  // AI_CHAT·SURVEY·CONSULTATION — 각 소비 spec(008·010·011)의 UI가 아직 없다.
  // 이벤트가 Dispatcher를 거쳐 여기까지 도달하는지 확인하기 위한 임시 플랫폼 동작일 뿐이다.
  return (
    <div>
      <p>이 기능은 준비 중입니다.</p>
      <button type="button" onClick={closeOverlay}>
        닫기
      </button>
    </div>
  );
}
