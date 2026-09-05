// Runtime Asset Delivery 해석 규칙 (S15P21A604-427, #128 §4) — base + manifest 상대경로 → 절대 URL.
import { describe, expect, it } from 'vitest';
import { normalizeAssetBase, resolveAssetUrl } from '../resolveAssetUrl';

const ORIGIN = 'https://demo.example.com';

describe('normalizeAssetBase', () => {
  it('절대 base 는 끝 슬래시를 보장한다', () => {
    expect(normalizeAssetBase('https://cdn.example.com/unity', ORIGIN).toString()).toBe('https://cdn.example.com/unity/');
    expect(normalizeAssetBase('https://cdn.example.com/unity/', ORIGIN).toString()).toBe('https://cdn.example.com/unity/');
  });

  it('오리진 상대 base(/unity/)는 현재 오리진 기준으로 절대화한다 — same-origin 서빙(#127 안 A)', () => {
    expect(normalizeAssetBase('/unity/', ORIGIN).toString()).toBe('https://demo.example.com/unity/');
  });

  it('빈 base·해석 불가 base 는 이름을 말하며 실패한다', () => {
    expect(() => normalizeAssetBase('   ', ORIGIN)).toThrow('비어 있다');
    expect(() => normalizeAssetBase('http://', ORIGIN)).toThrow('해석할 수 없다');
  });
});

describe('resolveAssetUrl', () => {
  const base = normalizeAssetBase('https://cdn.example.com/unity/v1', ORIGIN);

  it('manifest 상대경로(Build/<hash>.loader.js)를 base 기준으로 푼다 — FE 오리진 기준이 아니다', () => {
    expect(resolveAssetUrl('Build/abc.loader.js', base)).toBe('https://cdn.example.com/unity/v1/Build/abc.loader.js');
  });

  it('절대 URL 은 그대로 통과한다', () => {
    expect(resolveAssetUrl('https://other.example.com/x.wasm', base)).toBe('https://other.example.com/x.wasm');
  });

  it('루트 상대(/Build/..)는 base 오리진 기준이다 — base 경로를 벗어난다는 뜻이라 manifest 는 이 형태를 쓰지 않는다', () => {
    expect(resolveAssetUrl('/Build/abc.data', base)).toBe('https://cdn.example.com/Build/abc.data');
  });
});
