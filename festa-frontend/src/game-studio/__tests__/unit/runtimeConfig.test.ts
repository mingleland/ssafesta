// Game Studio 서버 저작 플래그의 계약을 고정한다 (GitLab #258).
//
// 값이 없으면 ON 이다. 이전에는 없으면 OFF 였고 배포 빌드만 `ci/build` 가 env 를 끼워 넣어 ON 을 만들었는데,
// 그 결과 제품 기능의 on/off 를 CI 스크립트가 쥐고 있었다(#245·#258). 기본값이 뒤집혔다는 사실 자체가
// 여기서 깨져야 다음 사람이 알아차린다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { isBrowserPublicationEnabled, isServerAuthoringEnabled } from '../../app/runtimeConfig.ts';

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('Game Studio 서버 저작 플래그', () => {
  it('값이 없으면 서버 저작이 켜진다 — 주지 않으면 꺼지던 기본값을 뒤집었다', () => {
    vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', undefined as unknown as string);
    expect(isServerAuthoringEnabled()).toBe(true);
  });

  it("'true' 면 켜진다", () => {
    vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'true');
    expect(isServerAuthoringEnabled()).toBe(true);
  });

  it("'false' 를 명시해야만 꺼진다", () => {
    vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'false');
    expect(isServerAuthoringEnabled()).toBe(false);
  });

  it('브라우저 게시는 mock 이면서 서버 저작을 끈 경우에만 켜진다 — VITE_USE_MOCK 계약은 그대로다', () => {
    vi.stubEnv('VITE_USE_MOCK', 'true');
    vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'false');
    expect(isBrowserPublicationEnabled()).toBe(true);

    vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'true');
    expect(isBrowserPublicationEnabled()).toBe(false);

    // mock 이 아니면 서버 저작을 꺼도 브라우저 게시는 켜지지 않는다.
    vi.stubEnv('VITE_USE_MOCK', 'false');
    vi.stubEnv('VITE_GAME_STUDIO_API_ENABLED', 'false');
    expect(isBrowserPublicationEnabled()).toBe(false);
  });
});

