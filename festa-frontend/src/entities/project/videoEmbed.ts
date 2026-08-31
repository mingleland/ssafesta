// Project 전시 영상 주소 판정 (spec 009 FR-004·FR-009, D09).
//
// 세 갈래로만 답한다. 방문자가 결국 영상에 도달하는 것이 목표이므로(SC-003), 임베드할 수
// 없는 주소를 "오류"로 접지 않는다 — 새 탭으로 열 수 있으면 그렇게 안내한다.
//
//   EMBED      YouTube 임베드가 가능하다
//   LINK_ONLY  형식은 맞지만 임베드는 못 한다 — 새 탭 대안 (FR-009)
//   INVALID    주소로 쓸 수 없다 (FR-004: http/https 만)
//
// ⚠️ C-02(지원 영상 제공자 범위)는 아직 **미결**이다 — spec 009 spec.md:84 "기획 대기".
// 그래서 이 파일은 제공자를 **거르지 않는다.** YouTube 만 임베드하고 나머지는 LINK_ONLY 로
// 내보낸다 — 저장을 막지도, 화면에서 감추지도 않는다. C-02 가 무엇으로 정해지든(무제한이든
// 목록이든) 이 동작은 어긋나지 않고, 목록으로 좁혀지면 spec 이 정한 대로 **서버가 등록 시점에
// 거부**한다. "통과시키고 화면에서만 안 나오는 조합"을 만들지 않는 것이 그 합의다 (T-24).
//
// 판정 전용이다. 렌더는 이 결과를 읽는 쪽이 한다.

export type VideoEmbed =
  | { kind: 'EMBED'; videoId: string; embedUrl: string; watchUrl: string }
  | { kind: 'LINK_ONLY'; href: string; reason: 'NOT_EMBEDDABLE_PROVIDER' }
  | { kind: 'INVALID'; reason: 'EMPTY' | 'MALFORMED' | 'UNSUPPORTED_SCHEME' };

/** YouTube 영상 id. 11자 고정이고 URL 안전 문자만 쓴다. */
const VIDEO_ID = /^[\w-]{11}$/;

const YOUTUBE_HOSTS = new Set([
  'youtube.com',
  'www.youtube.com',
  'm.youtube.com',
  'music.youtube.com',
  'youtube-nocookie.com',
  'www.youtube-nocookie.com',
]);
const SHORT_HOSTS = new Set(['youtu.be', 'www.youtu.be']);

export function parseVideoEmbed(raw: string | null | undefined): VideoEmbed {
  const trimmed = (raw ?? '').trim();
  if (trimmed === '') return { kind: 'INVALID', reason: 'EMPTY' };

  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return { kind: 'INVALID', reason: 'MALFORMED' };
  }

  // FR-004 — 스킴 검증. `javascript:` 같은 것을 링크로 넘기면 그 자체가 구멍이다.
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return { kind: 'INVALID', reason: 'UNSUPPORTED_SCHEME' };
  }

  const videoId = youtubeVideoId(url);
  if (videoId) {
    return {
      kind: 'EMBED',
      videoId,
      // nocookie 로 고정한다 — 전시 페이지가 방문자에게 추적 쿠키를 심을 이유가 없다.
      embedUrl: `https://www.youtube-nocookie.com/embed/${videoId}`,
      watchUrl: `https://www.youtube.com/watch?v=${videoId}`,
    };
  }

  // 형식은 맞는데 임베드는 못 한다 — 막힌 것이 아니라 새 탭으로 가는 길이다 (FR-009).
  return { kind: 'LINK_ONLY', href: url.toString(), reason: 'NOT_EMBEDDABLE_PROVIDER' };
}

/** URL 이 가리키는 YouTube 영상 id. 아니면 null — 추측하지 않는다. */
function youtubeVideoId(url: URL): string | null {
  const host = url.hostname.toLowerCase();

  if (SHORT_HOSTS.has(host)) {
    const candidate = url.pathname.slice(1).split('/')[0] ?? '';
    return VIDEO_ID.test(candidate) ? candidate : null;
  }

  if (!YOUTUBE_HOSTS.has(host)) return null;

  const fromQuery = url.searchParams.get('v');
  if (fromQuery && VIDEO_ID.test(fromQuery)) return fromQuery;

  // /embed/<id>, /shorts/<id>, /live/<id> 는 같은 영상의 다른 표기다.
  const [first, second] = url.pathname.split('/').filter((part) => part !== '');
  if (first && second && ['embed', 'shorts', 'live', 'v'].includes(first) && VIDEO_ID.test(second)) {
    return second;
  }
  return null;
}
