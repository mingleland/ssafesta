// Overlay Bus의 현재 요청을 실제 화면으로 그리는 유일한 지점. Unity 이벤트는 모른다 —
// 그건 Interaction Dispatcher 몫이다(features/interaction/dispatcher.ts). 이 파일은 렌더링만 한다.
import { useSyncExternalStore } from 'react';
import { closeOverlay, getCurrentOverlay, subscribeOverlay } from '../../shared/types/overlay';
import { LaptopOverlay } from './LaptopOverlay';

function subscribe(onStoreChange: () => void): () => void {
  return subscribeOverlay(() => onStoreChange());
}

export function OverlayHost() {
  const request = useSyncExternalStore(subscribe, getCurrentOverlay);

  if (!request) return null;

  if (request.type === 'LAPTOP') {
    return <LaptopOverlay payload={request.payload as { boothId: number; objectId: string; url?: string }} />;
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
