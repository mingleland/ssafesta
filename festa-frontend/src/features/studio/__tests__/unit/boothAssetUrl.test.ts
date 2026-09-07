// @vitest-environment jsdom
// Booth 에셋 URL 해석이 Unity 빌드와 **같은 규칙**을 쓰는지 잠근다 (S15P21A604-473).
//
// 처음엔 boothAssetManifest 가 import.meta.env.BASE_URL 을 직접 만졌다. 결과는 같았지만
// 규칙이 둘이었고, base 가 CDN 으로 바뀌는 순간 갈라진다 — S15P21A604-427 이 막으려던 그것이다.
// 여기서 보는 것은 "값이 맞나" 가 아니라 "한 규칙을 쓰나" 다.
import { afterEach, describe, expect, it } from 'vitest';
import { normalizeAssetBase, resolveAssetUrl } from '../../../../shared/assets/resolveAssetUrl';
import { boothAssetBase } from '../../../../shared/config/runtime';
import { BOOTH_ASSET_MANIFEST_URL, boothAssetBaseUrl } from '../../model/boothAssetManifest';

afterEach(() => {
  delete window.__FESTA_CONFIG__;
});

describe('boothAssetBase', () => {
  it('런타임 주입이 없으면 앱 base 로 내려간다 — 에셋이 FE 정적 자원으로 나가는 현재 배치', () => {
    expect(boothAssetBase()).toBe(import.meta.env.BASE_URL || '/');
  });

  it('런타임 주입이 있으면 그것을 쓴다 — CDN 으로 옮길 때 코드가 아니라 주입만 바뀐다', () => {
    window.__FESTA_CONFIG__ = { boothAssetBase: 'https://cdn.example.test/booth/' };
    expect(boothAssetBase()).toBe('https://cdn.example.test/booth/');
  });

  it('Unity base 와 섞이지 않는다 — 둘은 base 를 공유하지 않는다', () => {
    window.__FESTA_CONFIG__ = { unityBuildBase: 'https://cdn.example.test/unity/' };
    expect(boothAssetBase()).toBe(import.meta.env.BASE_URL || '/');
  });
});

describe('URL 해석 — shared seam 하나만 쓴다', () => {
  it('manifest 경로가 shared 규칙으로 푼 것과 정확히 같다', () => {
    const expected = resolveAssetUrl(BOOTH_ASSET_MANIFEST_URL, normalizeAssetBase(boothAssetBase()));
    expect(resolveAssetUrl(BOOTH_ASSET_MANIFEST_URL, boothAssetBaseUrl())).toBe(expected);
  });

  it('CDN base 에서도 이중 슬래시가 생기지 않는다 — 문자열 이어붙이기가 깨지던 지점이다', () => {
    window.__FESTA_CONFIG__ = { boothAssetBase: 'https://cdn.example.test/booth' };
    expect(resolveAssetUrl('assets/booth/A.glb', boothAssetBaseUrl())).toBe(
      'https://cdn.example.test/booth/assets/booth/A.glb',
    );
  });

  it('manifest 가 절대 URL 을 담아도 그대로 통과한다 — 생성자가 형식을 바꿔도 소비처는 안 바뀐다', () => {
    const absolute = 'https://other.example.test/x.glb';
    expect(resolveAssetUrl(absolute, boothAssetBaseUrl())).toBe(absolute);
  });

  it('base 끝 슬래시 유무가 결과를 바꾸지 않는다', () => {
    window.__FESTA_CONFIG__ = { boothAssetBase: 'https://cdn.example.test/booth' };
    const withoutSlash = resolveAssetUrl('assets/booth/A.glb', boothAssetBaseUrl());
    window.__FESTA_CONFIG__ = { boothAssetBase: 'https://cdn.example.test/booth/' };
    const withSlash = resolveAssetUrl('assets/booth/A.glb', boothAssetBaseUrl());
    expect(withoutSlash).toBe(withSlash);
  });
});
