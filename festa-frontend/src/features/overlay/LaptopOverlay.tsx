// spec 016 US1·US3 — 노트북 오브젝트 상호작용의 React 표현. iframe 성공 여부는 브라우저
// 보안 정책(X-Frame-Options·CSP frame-ancestors 등) 때문에 FE가 신뢰성 있게 감지할 수 없다
// (spec 016 기술 리스크 §1·§2) — 그래서 "차단 감지 후 대안"이 아니라 iframe과 새 탭을
// 처음부터 동시에 제공한다. 새 탭은 실패 시 나타나는 2차 기능이 아니라 1급 기능이다.
//
// URL 의 정본은 GET /booths/{id} 의 homepageUrl 이다(016 C-01 #97) — 이벤트 payload 의 url 은
// 소비하지 않는다. Unity 는 url 을 보내지 않으므로 payload 의존은 항상 "미준비"로 떨어졌다(-374).
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { closeOverlay } from '../../shared/types/overlay';
import { loadLaptopHomepage, resetLaptopHomepage, useLaptopHomepage } from './model/laptopHomepage';
import { OverlayEmpty, OverlayError, OverlayFrame, OverlayLoading } from './ui/OverlayFrame';
import './ui/laptopOverlay.css';

const IcLaptop = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="4" y="5" width="16" height="11" rx="2" />
    <path d="M2 20h20" />
  </svg>
);

interface LaptopOverlayPayload {
  boothId: number;
  objectId: string;
}

/**
 * 노트북 화면이 남에게 보여 주는 **뷰포트 크기**. 창이 얼마나 크든 이 값이 고정이다.
 *
 * 상자를 창에 맞춰 늘리는 방식으로는 이걸 보장할 수 없다 — Overlay 는 사방 `4vw/4vh` 를 비우므로
 * 쓸 수 있는 폭이 `92vw` 이고, 노트북 화면을 1400px 안팎의 창에서 열면 1280 에 닿기 전에 창이
 * 먼저 걸린다. 그러면 사이트는 태블릿 레이아웃을 내주고, 우리는 무엇이 올지 통제하지 못한다.
 *
 * 그래서 iframe 은 **항상 1280×720 으로 그리고**, 들어갈 자리가 좁으면 통째로 축소해서 앉힌다.
 * 사이트가 보는 뷰포트는 그대로 1280 이고 눈에만 작아진다. 확대는 하지 않는다 — 1280 보다 넓은
 * 자리에서 늘리면 없는 해상도를 지어내는 셈이라 글자만 뭉갠다.
 */
const SCREEN_W = 1280;
const SCREEN_H = 720;

function openInNewTab(href: string): void {
  window.open(href, '_blank', 'noopener,noreferrer');
}

/**
 * iframe 에 위임할 권한 (S15P21A604-946).
 *
 * `allow` 속성이 없으면 권한이 필요한 기능은 iframe 안에서 **전부 거부**다 — 대부분의 기본
 * allowlist 가 `self` 라 교차 출처 프레임에는 아무것도 내려가지 않는다. 그래서 값이 없던 것은
 * "기본값" 이 아니라 "전부 차단" 이었다.
 *
 * <b>`clipboard-read`·`camera`·`microphone`·`geolocation` 은 넣지 않는다.</b> 소유자가 등록한
 * 임의 사이트에 방문자의 클립보드·카메라·마이크·위치를 열어 줄 이유가 없다. 여기 없는 기능은
 * 브라우저 기본 정책을 그대로 따른다.
 */
const IFRAME_ALLOW = 'autoplay; clipboard-write; fullscreen; encrypted-media; picture-in-picture';

/**
 * sandbox 토큰 (S15P21A604-946).
 *
 * <b>`allow-popups-to-escape-sandbox` 가 이번 변경의 핵심이다.</b> 이것이 없으면 프레임이 연 팝업이
 * 부모의 sandbox 를 그대로 물려받아 정상 최상위 창이 되지 못하고, 그 상태에서 깨지는 것이 팝업형
 * OAuth/SSO 다. 외부 서비스 자체 정책·third-party storage 제한·IdP 정책으로 실패하는 경우까지
 * 이것으로 풀리지는 않는다.
 *
 * <b>`allow-top-navigation` 계열은 넣지 않는다.</b> 넣으면 프레임 안 사이트가 SSAFESTA 탭 전체를
 * 밖으로 보낼 수 있고, 그 순간 `PersistentWorld` 의 Unity 인스턴스가 죽어 재진입에 콜드 로딩을
 * 다시 문다(-620). redirect 형 OAuth 는 아래 '새 탭에서 열기' 가 받는다.
 *
 * `allow-same-origin` 은 교차 출처 콘텐츠에만 건다 — 같은 출처면 프레임이 제 sandbox 를 걷어낼 수
 * 있어서, 그 경우는 애초에 iframe 에 넣지 않는다(`resolveLaptopHomepage` 의 `self_origin`).
 */
const IFRAME_SANDBOX = [
  'allow-scripts',
  'allow-same-origin',
  'allow-forms',
  'allow-popups',
  'allow-popups-to-escape-sandbox',
  'allow-downloads',
  'allow-modals',
  'allow-storage-access-by-user-activation',
].join(' ');

/** 창 안에서 못 여는 이유 — 사용자가 다음에 할 일(새 탭)이 같으므로 문구만 가른다. */
const EXTERNAL_ONLY_COPY: Record<'insecure' | 'self_origin', { title: string; hint: string }> = {
  insecure: {
    title: '이 주소는 창 안에서 열 수 없습니다',
    hint: 'http 로 시작하는 주소라 보안 연결 안에 표시되지 않습니다. 새 탭에서 열면 그대로 보입니다.',
  },
  self_origin: {
    title: '이 주소는 창 안에서 열 수 없습니다',
    hint: '축제장 자신을 가리키는 주소입니다. 새 탭에서 열어 주세요.',
  },
};

export function LaptopOverlay({ payload }: { payload: LaptopOverlayPayload }) {
  const homepage = useLaptopHomepage();

  useEffect(() => {
    void loadLaptopHomepage(payload.boothId);
    return () => resetLaptopHomepage();
  }, [payload.boothId]);

  const ready = homepage.kind === 'valid';
  const externalOnly = homepage.kind === 'external_only';

  // 들어갈 자리를 재서 축소 배율을 정한다. 창 크기·오버레이 여백·머리/바닥 높이가 모두 섞이는
  // 값이라 CSS 로는 계산할 수 없다 — 실제로 남은 상자를 재는 쪽이 맞다.
  //
  // **배율만 정하면 좌우가 뜬다.** 세로가 먼저 걸리면 비율을 지키느라 가로가 남고, 프레임은
  // 여전히 최대 폭이라 그 차이가 빈 여백으로 보인다. 그래서 배율과 함께 프레임 폭도 줄여
  // 축소된 화면을 그대로 감싸게 한다.
  //
  // 창 resize 에만 반응한다 — 프레임 폭을 우리가 건드리므로, 상자 크기를 관찰하면 그 변화가
  // 다시 계산을 부르는 고리가 된다.
  const screenRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);
  useLayoutEffect(() => {
    const box = screenRef.current;
    const frame = box?.closest<HTMLElement>('.festa-overlay-frame') ?? null;
    if (box === null || frame === null) return;

    const fit = () => {
      // 우리가 준 폭을 먼저 걷어야 CSS 가 정한 최대치를 잴 수 있다. 그 상태의 프레임과 화면
      // 상자의 차이가 곧 머리·바닥·여백이고, 폭을 줄여도 그 값은 변하지 않는다.
      frame.style.width = '';
      const chrome = frame.clientWidth - box.clientWidth;
      const room = { width: box.clientWidth, height: box.clientHeight };
      if (room.width === 0 || room.height === 0) return;
      const next = Math.min(1, room.width / SCREEN_W, room.height / SCREEN_H);
      setScale(next);
      frame.style.width = String(Math.round(SCREEN_W * next + chrome)) + 'px';
    };

    fit();
    window.addEventListener('resize', fit);
    return () => {
      window.removeEventListener('resize', fit);
      frame.style.width = '';
    };
  }, [ready]);
  // 새 탭은 둘 다 연다 — 창 안에서 못 여는 것과 아예 못 가는 것은 다르다(016 SC-003).
  const openable = ready || externalOnly;

  return (
    <OverlayFrame
      title="부스 홈페이지"
      subtitle={openable ? homepage.hostname : '노트북'}
      // 안쪽 iframe 이 1280×720 이 되도록 잡은 크기다 — 상세는 overlayFrame.css 의 screen 절
      size="screen"
      icon={IcLaptop}
      onClose={closeOverlay}
      status={<span className="ov-note">Esc 또는 바깥을 눌러 월드로 돌아갑니다</span>}
      footer={
        <>
          {openable && (
            <button type="button" className="ov-btn ov-btn-primary" onClick={() => openInNewTab(homepage.href)}>
              새 탭에서 열기
            </button>
          )}
          <button type="button" className="ov-btn" onClick={closeOverlay}>
            닫기
          </button>
        </>
      }
    >
      {(homepage.kind === 'idle' || homepage.kind === 'loading') && <OverlayLoading label="홈페이지를 불러오는 중..." />}
      {homepage.kind === 'no_url' && (
        <OverlayEmpty title="아직 홈페이지가 준비되지 않았습니다" hint="부스 주인이 홈페이지를 등록하면 여기에서 열립니다." />
      )}
      {homepage.kind === 'invalid' && <OverlayError title="유효하지 않은 주소입니다" message="등록된 홈페이지 주소를 열 수 없습니다." />}
      {/* 주소는 멀쩡하지만 이 창 안에서는 못 연다 — iframe 을 아예 걸지 않고 새 탭으로 보낸다.
          빈 프레임을 띄워 놓고 브라우저가 막게 두면 사용자에게는 이유 없는 흰 화면만 남는다. */}
      {homepage.kind === 'external_only' && (
        <OverlayEmpty
          title={EXTERNAL_ONLY_COPY[homepage.reason].title}
          hint={EXTERNAL_ONLY_COPY[homepage.reason].hint}
        />
      )}
      {homepage.kind === 'error' && (
        <OverlayError
          title="홈페이지를 불러오지 못했습니다"
          message="잠시 후 다시 시도해 주세요."
          onRetry={() => void loadLaptopHomepage(payload.boothId)}
        />
      )}
      {ready && (
        <div className="laptop-view">
          {/* 소유자가 등록한 임의 URL — sandbox 로 top 탐색(frame-busting)을 계속 차단한다 (-377).
              allow-same-origin 은 외부 origin 콘텐츠라 sandbox 우회로 이어지지 않는다(같은 출처는
              여기까지 오지 않는다 — self_origin 으로 갈린다). 토큰·권한의 근거는 위 상수 주석에 있다. */}
          <div ref={screenRef} className="laptop-screen">
            <iframe
              className="laptop-iframe"
              style={{ width: SCREEN_W, height: SCREEN_H, transform: `scale(${scale})` }}
              src={homepage.href}
              title={homepage.hostname}
              sandbox={IFRAME_SANDBOX}
              allow={IFRAME_ALLOW}
              allowFullScreen
            />
          </div>
          <p className="ov-note">사이트 정책에 따라 여기 표시되지 않을 수 있습니다 — 그때는 새 탭으로 열어 주세요.</p>
        </div>
      )}
    </OverlayFrame>
  );
}
