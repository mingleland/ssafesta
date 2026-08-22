// spec 016 US1·US3 — 노트북 오브젝트 상호작용의 React 표현. iframe 성공 여부는 브라우저
// 보안 정책(X-Frame-Options·CSP frame-ancestors 등) 때문에 FE가 신뢰성 있게 감지할 수 없다
// (spec 016 기술 리스크 §1·§2) — 그래서 "차단 감지 후 대안"이 아니라 iframe과 새 탭을
// 처음부터 동시에 제공한다. 새 탭은 실패 시 나타나는 2차 기능이 아니라 1급 기능이다.
import { closeOverlay } from '../../shared/types/overlay';

interface LaptopOverlayPayload {
  boothId: number;
  objectId: string;
  url?: string;
}

type UrlState =
  | { kind: 'NO_URL' }
  | { kind: 'INVALID_URL' }
  | { kind: 'VALID_URL'; href: string; hostname: string };

// 계약(events.ts BoothInteractEvent)의 url은 Unity가 그대로 보낸 문자열이라 신뢰하지 않는다 —
// iframe·새 탭에 넣기 전 여기서 한 번만 정규화한다. http/https 외 scheme(javascript:·data: 등)은 거부.
function resolveUrl(raw: string | undefined): UrlState {
  if (raw === undefined) return { kind: 'NO_URL' };
  try {
    const parsed = new URL(raw, window.location.href);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return { kind: 'INVALID_URL' };
    return { kind: 'VALID_URL', href: parsed.href, hostname: parsed.hostname };
  } catch {
    return { kind: 'INVALID_URL' };
  }
}

function openInNewTab(href: string): void {
  window.open(href, '_blank', 'noopener,noreferrer');
}

export function LaptopOverlay({ payload }: { payload: LaptopOverlayPayload }) {
  const urlState = resolveUrl(payload.url);

  if (urlState.kind === 'NO_URL') {
    return (
      <div>
        <p>아직 홈페이지가 준비되지 않았습니다.</p>
        <button type="button" onClick={closeOverlay}>
          닫기
        </button>
      </div>
    );
  }

  if (urlState.kind === 'INVALID_URL') {
    return (
      <div>
        <p>유효하지 않은 주소입니다.</p>
        <button type="button" onClick={closeOverlay}>
          닫기
        </button>
      </div>
    );
  }

  return (
    <div>
      <div>
        <span>{urlState.hostname}</span>
        <button type="button" onClick={() => openInNewTab(urlState.href)}>
          새 탭에서 열기
        </button>
        <button type="button" onClick={closeOverlay}>
          닫기
        </button>
      </div>
      <iframe src={urlState.href} title={urlState.hostname} style={{ width: '100%', height: '100%', border: 0 }} />
      <p>표시되지 않는 경우 새 탭에서 열어주세요.</p>
    </div>
  );
}
