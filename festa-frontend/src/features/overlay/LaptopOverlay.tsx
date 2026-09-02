// spec 016 US1·US3 — 노트북 오브젝트 상호작용의 React 표현. iframe 성공 여부는 브라우저
// 보안 정책(X-Frame-Options·CSP frame-ancestors 등) 때문에 FE가 신뢰성 있게 감지할 수 없다
// (spec 016 기술 리스크 §1·§2) — 그래서 "차단 감지 후 대안"이 아니라 iframe과 새 탭을
// 처음부터 동시에 제공한다. 새 탭은 실패 시 나타나는 2차 기능이 아니라 1급 기능이다.
//
// URL 의 정본은 GET /booths/{id} 의 homepageUrl 이다(016 C-01 #97) — 이벤트 payload 의 url 은
// 소비하지 않는다. Unity 는 url 을 보내지 않으므로 payload 의존은 항상 "미준비"로 떨어졌다(-374).
import { useEffect } from 'react';
import { closeOverlay } from '../../shared/types/overlay';
import { loadLaptopHomepage, resetLaptopHomepage, useLaptopHomepage } from './model/laptopHomepage';

interface LaptopOverlayPayload {
  boothId: number;
  objectId: string;
}

function openInNewTab(href: string): void {
  window.open(href, '_blank', 'noopener,noreferrer');
}

export function LaptopOverlay({ payload }: { payload: LaptopOverlayPayload }) {
  const homepage = useLaptopHomepage();

  useEffect(() => {
    void loadLaptopHomepage(payload.boothId);
    return () => resetLaptopHomepage();
  }, [payload.boothId]);

  if (homepage.kind === 'idle' || homepage.kind === 'loading') {
    return (
      <div>
        <p>홈페이지를 불러오는 중입니다.</p>
        <button type="button" onClick={closeOverlay}>
          닫기
        </button>
      </div>
    );
  }

  if (homepage.kind === 'no_url') {
    return (
      <div>
        <p>아직 홈페이지가 준비되지 않았습니다.</p>
        <button type="button" onClick={closeOverlay}>
          닫기
        </button>
      </div>
    );
  }

  if (homepage.kind === 'invalid' || homepage.kind === 'error') {
    return (
      <div>
        <p>{homepage.kind === 'invalid' ? '유효하지 않은 주소입니다.' : '홈페이지를 불러오지 못했습니다.'}</p>
        <button type="button" onClick={closeOverlay}>
          닫기
        </button>
      </div>
    );
  }

  return (
    <div>
      <div>
        <span>{homepage.hostname}</span>
        <button type="button" onClick={() => openInNewTab(homepage.href)}>
          새 탭에서 열기
        </button>
        <button type="button" onClick={closeOverlay}>
          닫기
        </button>
      </div>
      {/* 소유자가 등록한 임의 URL — sandbox 로 top 탐색(frame-busting)을 차단한다 (-377).
          allow-same-origin 은 외부 origin 콘텐츠라 sandbox 우회로 이어지지 않는다. */}
      <iframe
        src={homepage.href}
        title={homepage.hostname}
        sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
        style={{ width: '100%', height: '100%', border: 0 }}
      />
      <p>표시되지 않는 경우 새 탭에서 열어주세요.</p>
    </div>
  );
}
