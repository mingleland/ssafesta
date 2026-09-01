// S15P21A604-195 완료 조건 — 정상·차단·오류 URL 3케이스가 각각 다른 결과를 낸다.
//
// 세 갈래를 한 파일에서 나란히 두는 이유: 이 셋을 뭉개는 것이 이 작업이 막으려는 실패다.
// 임베드 불가를 오류로 접으면 방문자가 영상에 도달할 길이 사라진다 (spec 009 SC-003).

import { describe, expect, it } from 'vitest';
import { parseVideoEmbed } from '../../videoEmbed.ts';

describe('parseVideoEmbed — 정상 URL', () => {
  it('watch 주소를 임베드로 판정하고 영상 id 를 뽑는다', () => {
    const result = parseVideoEmbed('https://www.youtube.com/watch?v=dQw4w9WgXcQ');

    expect(result.kind).toBe('EMBED');
    if (result.kind !== 'EMBED') return;
    expect(result.videoId).toBe('dQw4w9WgXcQ');
    expect(result.embedUrl).toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ');
    expect(result.watchUrl).toBe('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
  });

  it('단축·embed·shorts 표기도 같은 영상으로 읽는다', () => {
    const forms = [
      'https://youtu.be/dQw4w9WgXcQ',
      'https://www.youtube.com/embed/dQw4w9WgXcQ',
      'https://www.youtube.com/shorts/dQw4w9WgXcQ',
      'https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=30s',
    ];

    for (const form of forms) {
      const result = parseVideoEmbed(form);
      expect(result.kind, form).toBe('EMBED');
      if (result.kind === 'EMBED') expect(result.videoId, form).toBe('dQw4w9WgXcQ');
    }
  });
});

describe('parseVideoEmbed — 임베드가 막힌 URL', () => {
  it('YouTube 가 아닌 영상 주소는 오류가 아니라 새 탭 대안이다 (FR-009)', () => {
    const result = parseVideoEmbed('https://vimeo.com/76979871');

    expect(result.kind).toBe('LINK_ONLY');
    if (result.kind !== 'LINK_ONLY') return;
    expect(result.reason).toBe('NOT_EMBEDDABLE_PROVIDER');
    expect(result.href).toBe('https://vimeo.com/76979871');
  });

  it('YouTube 도메인이지만 영상 id 가 없으면 임베드하지 않고 링크로 남긴다', () => {
    const result = parseVideoEmbed('https://www.youtube.com/@somechannel');

    expect(result.kind).toBe('LINK_ONLY');
  });

  it('id 형식이 어긋나면 임베드로 밀어붙이지 않는다', () => {
    const result = parseVideoEmbed('https://youtu.be/short');

    expect(result.kind).toBe('LINK_ONLY');
  });
});

describe('parseVideoEmbed — 잘못된 URL', () => {
  it('주소로 읽히지 않으면 오류다', () => {
    const result = parseVideoEmbed('그냥 문자열');

    expect(result.kind).toBe('INVALID');
    if (result.kind === 'INVALID') expect(result.reason).toBe('MALFORMED');
  });

  it('http/https 가 아닌 스킴은 링크로도 내보내지 않는다 (FR-004)', () => {
    for (const raw of ['javascript:alert(1)', 'data:text/html,<b>x</b>', 'file:///etc/passwd']) {
      const result = parseVideoEmbed(raw);
      expect(result.kind, raw).toBe('INVALID');
      if (result.kind === 'INVALID') expect(result.reason, raw).toBe('UNSUPPORTED_SCHEME');
    }
  });

  it('빈 값과 없는 값을 같은 자리에서 답한다', () => {
    for (const raw of ['', '   ', null, undefined]) {
      const result = parseVideoEmbed(raw);
      expect(result.kind).toBe('INVALID');
      if (result.kind === 'INVALID') expect(result.reason).toBe('EMPTY');
    }
  });
});
