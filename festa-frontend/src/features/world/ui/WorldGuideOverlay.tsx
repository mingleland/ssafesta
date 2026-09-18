// 월드 이용 안내 Overlay (S15P21A604-599) — 첫 진입 환영 화면.
//
// 세 동선(부스 → 콘텐츠 → 상담)을 카드로 보여 주고, 핵심 조작 4키와 참여 혜택을 요약한다.
// 전체 조작표는 ESC 메뉴의 조작 안내 오버레이가 담당한다 — 달리기·점프 같은 확장 키는 여기 없다.
//
// 프레임 헤더 대신 **자체 헤더**(로고 + 중앙 제목 + 닫기)를 그린다 — 로고가 프레임 상단 가운데
// 걸쳐야 해서다. OverlayFrame 은 aria-label 로 여전히 이 화면의 이름을 갖고, ESC 중재·dim 클릭·
// focus 반환이 전부 프레임을 따라온다 — 이 파일에는 그중 어느 것도 없다.
import { closeOverlay } from '../../../shared/types/overlay';
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { markWorldGuideSeen } from '../model/worldGuide';
import './worldGuide.css';

function icon(path: string) {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={path} />
    </svg>
  );
}

const STEPS = [
  {
    tint: 'booth',
    icon: 'M4 9h16M5 9V6a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3M6 9v10h12V9',
    title: '부스를 둘러보세요',
    desc: '프로젝트 · AI 직원 · 설문을 만나볼 수 있어요',
  },
  {
    tint: 'content',
    icon: 'M6 12h4M8 10v4M15 11h.01M17.5 13h.01M4 8h16v8H4z',
    title: '다양한 콘텐츠에 참여하세요',
    desc: '이벤트 · 설문 · 미니게임을 즐길 수 있어요',
  },
  {
    tint: 'help',
    icon: 'M21 12a8 8 0 0 1-8 8H5l-2 2V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2ZM8.5 11h.01M12 11h.01M15.5 11h.01',
    title: '도움이 필요하면 상담을 이용하세요',
    desc: 'AI 직원과 대화하거나 상담을 요청할 수 있어요',
  },
] as const;

export function WorldGuideOverlay() {
  // 열렸다는 사실 자체가 "봤다" 이다 — 닫는 방법(배경·X·ESC)마다 따로 기록하면 하나를 빠뜨린다.
  markWorldGuideSeen();

  return (
    <OverlayFrame
      title="SSAFESTA에 오신 걸 환영해요"
      subtitle="축제장을 돌아다니며 프로젝트와 다양한 콘텐츠를 만나보세요"
      size="m"
      onClose={closeOverlay}
      status={<span className="ov-note">도움말은 ESC 메뉴에서 언제든 다시 볼 수 있어요</span>}
      footer={
        <button type="button" className="ov-btn ov-btn-primary" onClick={closeOverlay}>
          축제 둘러보기
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M4 12h15M13 6l6 6-6 6" />
          </svg>
        </button>
      }
    >
      <div className="wg-head">
        <button type="button" className="wg-close" onClick={closeOverlay} aria-label="닫기">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
        <h2 className="wg-title">SSAFESTA에 오신 걸 환영해요</h2>
        <p className="wg-sub">축제장을 돌아다니며 프로젝트와 다양한 콘텐츠를 만나보세요</p>
      </div>

      <ol className="wg-steps">
        {STEPS.map((step) => (
          <li className="wg-step" key={step.tint}>
            <span className="wg-ico" data-tint={step.tint} aria-hidden="true">{icon(step.icon)}</span>
            <span className="wg-step-body">
              <strong>{step.title}</strong>
              <span>{step.desc}</span>
            </span>
          </li>
        ))}
      </ol>

      <div className="wg-brief">
        <section>
          <h4>핵심 조작</h4>
          <ul className="wg-keys">
            <li><span className="wg-key">WASD</span>이동</li>
            <li><span className="wg-key">F</span>상호작용</li>
            <li><span className="wg-key">Enter</span>채팅</li>
            <li><span className="wg-key">Esc</span>메뉴</li>
          </ul>
        </section>
        <section>
          <h4>🎁 참여하면</h4>
          <p className="wg-coin">🪙 코인을 모을 수 있어요</p>
        </section>
      </div>
    </OverlayFrame>
  );
}
