// 월드 이용 안내 Overlay (S15P21A604-599).
//
// **조작표가 아니다.** WASD·F·Shift 는 `WorldHud` 좌하단 카드가 이미 말하고 있고(-592), 같은 내용을
// 두 곳에 두면 하나가 낡는다. 여기는 **"SSAFESTA 에서 무엇을 할 수 있는가"** 만 말한다 — 처음 들어온
// 사람이 월드를 한 바퀴 돌기 전에 알아야 하는 것들이다.
//
// Overlay Bus 로 열린다. 그래서 배타(다른 레이어가 걷힌다)·ESC(WorldPage 중재)·Unity 입력 잠금·
// focus 반환이 전부 따라온다 — 이 파일에는 그중 어느 것도 없다.
import { closeOverlay } from '../../../shared/types/overlay';
import { OverlayCardGrid } from '../../overlay/ui/OverlayCardGrid';
import type { OverlayCard } from '../../overlay/ui/OverlayCardGrid';
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { markWorldGuideSeen } from '../model/worldGuide';

const IcGuide = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="9" />
    <path d="M9.6 9.2a2.5 2.5 0 1 1 3.2 2.9c-.5.2-.8.7-.8 1.2v.4" />
    <path d="M12 17h.01" />
  </svg>
);

function icon(path: string) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={path} />
    </svg>
  );
}

// 월드에서 실제로 열 수 있는 것들이다. 없는 기능을 적지 않는다 — 안내가 거짓말이 되면
// 처음 온 사람이 그것을 찾아 헤맨다.
const CARDS: OverlayCard[] = [
  {
    id: 'booth',
    icon: icon('M4 9h16M5 9V6a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3M6 9v10h12V9'),
    title: '부스 둘러보기',
    desc: '축제장 부스에 들어가 전시·노트북·설문 오브젝트를 살펴볼 수 있어요.',
  },
  {
    id: 'event',
    icon: icon('M12 3v18M5 8h14M7 12h10M9 16h6'),
    title: '이벤트',
    desc: '이벤트 NPC 에게 말을 걸면 경품 상점이 열려요.',
  },
  {
    id: 'survey',
    icon: icon('M8 4h8a2 2 0 0 1 2 2v14l-6-3-6 3V6a2 2 0 0 1 2-2ZM9 9h6M9 13h4'),
    title: '설문 참여',
    desc: '부스 설문에 답하면 코인을 받고 이벤트 추첨에도 참여할 수 있어요.',
  },
  {
    id: 'consultation',
    icon: icon('M21 12a8 8 0 1 1-3.2-6.4M21 5v4h-4'),
    title: '상담 요청',
    desc: 'AI 직원과 먼저 대화하고, 더 필요하면 사람 상담을 요청할 수 있어요.',
  },
  {
    id: 'minigame',
    icon: icon('M6 12h4M8 10v4M15 11h.01M17.5 13h.01M4 8h16v8H4z'),
    title: '미니게임',
    desc: '광장과 11층 기계에서 미니게임을 즐길 수 있어요.',
  },
  {
    id: 'coin',
    icon: icon('M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16ZM12 8v8M9.5 10h5M9.5 14h5'),
    title: '코인',
    desc: '매일 접속하면 코인이 쌓이고, 부스 임대와 경품 교환에 쓸 수 있어요.',
  },
];

export function WorldGuideOverlay() {
  // 열렸다는 사실 자체가 "봤다" 이다 — 닫는 방법(배경·X·ESC)마다 따로 기록하면 하나를 빠뜨린다.
  markWorldGuideSeen();

  return (
    <OverlayFrame
      title="이용 안내"
      subtitle="SSAFESTA 에서 할 수 있는 것"
      size="m"
      icon={IcGuide}
      onClose={closeOverlay}
      footer={
        <button type="button" className="ov-btn ov-btn-primary" onClick={closeOverlay}>
          월드로 돌아가기
        </button>
      }
      status={<span className="ov-note">조작 방법은 화면 왼쪽 아래 안내를 보세요</span>}
    >
      <OverlayCardGrid cards={CARDS} label="이용 안내 항목" />
    </OverlayFrame>
  );
}
