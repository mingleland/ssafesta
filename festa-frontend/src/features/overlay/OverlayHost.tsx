// Overlay Bus의 현재 요청을 실제 화면으로 그리는 유일한 지점. Unity 이벤트는 모른다 —
// 그건 Interaction Dispatcher 몫이다(features/interaction/dispatcher.ts). 이 파일은 렌더링만 한다.
import { lazy, Suspense, useSyncExternalStore } from 'react';
import { closeOverlay, getCurrentOverlay, subscribeOverlay } from '../../shared/types/overlay';
import { LaptopOverlay } from './LaptopOverlay';
import { OverlayEmpty, OverlayFrame } from './ui/OverlayFrame';
import { ProjectOverlay } from '../project/ui/ProjectOverlay';
import { SurveyOverlay } from '../survey/ui/SurveyOverlay';
import { ConsultationOverlay } from '../consultation/ui/ConsultationOverlay';
import { AiChatOverlay } from '../ai/ui/AiChatOverlay';
import { WorldGuideOverlay } from '../world/ui/WorldGuideOverlay';
import { TimerStopOverlay } from '../minigame/ui/TimerStopOverlay';
import { EventRewardShopOverlay } from '../event/ui/EventRewardShopOverlay';
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

  if (request.type === 'PROJECT') {
    return <ProjectOverlay payload={request.payload as { boothId: number }} />;
  }

  if (request.type === 'SURVEY') {
    return <SurveyOverlay payload={request.payload as { boothId: number; surveyId?: string }} />;
  }

  if (request.type === 'CONSULTATION') {
    return <ConsultationOverlay payload={request.payload as { boothId: number }} />;
  }

  if (request.type === 'AI_CHAT') {
    return <AiChatOverlay payload={request.payload as { boothId: number; agentId?: number }} />;
  }

  // 부스에 속하지 않는 둘 — payload 가 없다. 같은 Bus 를 쓰는 이유는 배타·ESC·입력 잠금·focus 가
  // 이 길에 붙어 있기 때문이다(shared/types/overlay.ts 주석).
  if (request.type === 'WORLD_GUIDE') {
    return <WorldGuideOverlay />;
  }

  if (request.type === 'EVENT_SHOP') {
    return <EventRewardShopOverlay />;
  }

  if (request.type === 'MINIGAME') {
    // 내장 미니게임 (S15P21A604-601). GAME 과 달리 lazy 로 가르지 않는다 — 게임 런타임을 끌고
    // 오지 않아서 정적 import 로도 월드 진입 번들이 늘지 않는다.
    return <TimerStopOverlay />;
  }

  if (request.type === 'GAME') {
    return (
      <Suspense fallback={<p>게임을 여는 중입니다.</p>}>
        <GameOverlay payload={request.payload as GameOverlayPayload} />
      </Suspense>
    );
  }

  // 여기 남는 것은 이 편집기가 모르는 미래 타입뿐이다 — 조용히 무시하지 않고 알린다.
  // 프레임 밖 raw div 였던 자리다(-599) — 그러면 배경 클릭·focus 반환이 다른 오버레이와 갈라진다.
  return (
    <OverlayFrame title="준비 중" size="s" onClose={closeOverlay}>
      {/* 문구는 그대로 둔다 — 기존 계약 테스트가 이 문자열을 본다(overlayHostGameWiring.test) */}
      <OverlayEmpty title="이 기능은 준비 중입니다." />
    </OverlayFrame>
  );
}
