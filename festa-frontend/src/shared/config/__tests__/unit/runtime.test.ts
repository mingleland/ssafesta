// runtime config fallback 고정 — 런타임 주입이 실패했을 때 조용히 빈 base로 떨어지지 않는 것이 요지다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolveApiBaseUrl, resolveHostApiBaseUrl } from '../../runtime';

describe('resolveApiBaseUrl', () => {
  it('런타임 값이 있으면 그것을 쓴다 — 배포 이미지의 정상 경로', () => {
    expect(resolveApiBaseUrl('https://api.example.test', 'http://localhost:8080')).toBe('https://api.example.test');
  });

  it('런타임 값이 없으면 빌드타임 값으로 내려간다 — npm run dev의 정상 경로', () => {
    expect(resolveApiBaseUrl(undefined, 'http://localhost:8080')).toBe('http://localhost:8080');
  });

  it('런타임 값이 빈 문자열이어도 빌드타임 값으로 내려간다 — 주입이 비어 온 경우', () => {
    expect(resolveApiBaseUrl('', 'http://localhost:8080')).toBe('http://localhost:8080');
  });

  it('둘 다 없으면 상대 경로(빈 문자열) — same-origin 배치의 기본값', () => {
    expect(resolveApiBaseUrl(undefined, undefined)).toBe('');
  });
});

// FE Host Gateway (S15P21A604-564). 이 판정이 resolveApiBaseUrl 과 갈라진 이유는 하나다 —
// 같은 값을 Unity 도 읽는데, Unity 는 빈 문자열을 "주입 없음" 으로 읽어 빌드 타임 Prod 로 내려갔다.
const ORIGIN = 'http://localhost:5173';

describe('resolveHostApiBaseUrl — Spring API base', () => {
  it('런타임 주입이 있으면 그것을 쓴다 — 배포 이미지의 정상 경로', () => {
    expect(resolveHostApiBaseUrl('https://api.example.test', 'http://localhost:8080', ORIGIN))
      .toBe('https://api.example.test');
  });

  it('런타임 값이 없으면 빌드타임 값으로 내려간다', () => {
    expect(resolveHostApiBaseUrl(undefined, 'http://localhost:8080', ORIGIN))
      .toBe('http://localhost:8080');
  });

  it('둘 다 없으면 FE 오리진이다 — 게이트웨이가 흡수한다', () => {
    expect(resolveHostApiBaseUrl(undefined, undefined, ORIGIN)).toBe(ORIGIN);
  });

  it('상대 경로 주입은 오리진 기준으로 절대화한다 — Unity 는 상대 URL 을 받지 않는다', () => {
    expect(resolveHostApiBaseUrl('/__dev/api', undefined, 'http://10.0.0.1'))
      .toBe('http://10.0.0.1/__dev/api');
  });

  it('끝 슬래시를 남기지 않는다 — 소비처가 `${base}${path}` 로 이어 붙인다', () => {
    expect(resolveHostApiBaseUrl('https://api.example.test/', undefined, ORIGIN))
      .toBe('https://api.example.test');
  });

  // 이 단정이 무언 Prod fallback 을 막는 잠금이다. 빈 문자열이 다시 새어 나오면 jslib 가 0 을
  // 돌려주고 Unity 가 https://api.ssafesta.world 로 간다 — 로컬에서 월드가 조용히 안 뜨던 경로다.
  it.each([
    [undefined, undefined],
    ['', ''],
    ['', undefined],
    [undefined, ''],
  ])('브라우저에서는 어떤 입력에도 빈 문자열을 돌려주지 않는다 (%s, %s)', (runtime, build) => {
    expect(resolveHostApiBaseUrl(runtime, build, ORIGIN)).not.toBe('');
  });

  it('오리진을 알 수 없으면(node env·SSR) 주입값을 그대로 돌려준다 — 절대화 기준이 없다', () => {
    expect(resolveHostApiBaseUrl(undefined, undefined, '')).toBe('');
    expect(resolveHostApiBaseUrl('/__dev/api', undefined, '')).toBe('/__dev/api');
  });
});

// window 를 세워야 하는 것들 — 이 파일은 node env 라 직접 스텁한다(G-4 jsdom 전환 없이).
// 빌드타임 env 도 함께 비운다 — 개발자마다 다른 .env.local 값이 판정에 새어 들면 테스트가
// 그 사람 환경을 검증하게 된다(실제로 VITE_API_BASE_URL 이 들어와 처음 두 케이스가 뒤집혔다).
function stubBrowser(config?: Record<string, string>): void {
  vi.stubEnv('VITE_API_BASE_URL', '');
  vi.stubEnv('VITE_AUTH_BASE_URL', '');
  vi.stubGlobal('window', {
    location: { origin: ORIGIN },
    ...(config === undefined ? {} : { __FESTA_CONFIG__: config }),
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('publishHostApiBaseUrl — Unity 가 읽을 값 공급', () => {
  it('window.__FESTA_CONFIG__.apiBaseUrl 을 확정 값으로 채운다', async () => {
    stubBrowser();
    const { publishHostApiBaseUrl } = await import('../../runtime');

    expect(publishHostApiBaseUrl()).toBe(ORIGIN);
    expect(window.__FESTA_CONFIG__?.apiBaseUrl).toBe(ORIGIN);
  });

  it('이미 주입된 값이 있으면 그것을 지우지 않는다 — 배포 entrypoint 의 값이 이긴다', async () => {
    stubBrowser({ apiBaseUrl: 'https://api.example.test' });
    const { publishHostApiBaseUrl } = await import('../../runtime');

    expect(publishHostApiBaseUrl()).toBe('https://api.example.test');
    expect(window.__FESTA_CONFIG__?.apiBaseUrl).toBe('https://api.example.test');
  });
});

describe('authBaseUrl — 인증 계열 전용 base', () => {
  it('설정이 없으면 apiBaseUrl 과 같다 — 게이트웨이 없는 배포에서 회귀가 없다', async () => {
    stubBrowser();
    const { apiBaseUrl, authBaseUrl } = await import('../../runtime');

    expect(authBaseUrl()).toBe(apiBaseUrl());
  });

  it('설정이 있으면 일반 API 와 갈라진다 — 인증 쿠키가 host-only 라서다', async () => {
    stubBrowser();
    vi.stubEnv('VITE_AUTH_BASE_URL', 'http://localhost:8080');
    const { apiBaseUrl, authBaseUrl } = await import('../../runtime');

    expect(authBaseUrl()).toBe('http://localhost:8080');
    expect(authBaseUrl()).not.toBe(apiBaseUrl());
  });

  it('런타임 주입이 빌드타임 값을 이긴다', async () => {
    stubBrowser({ authBaseUrl: 'https://auth.example.test' });
    vi.stubEnv('VITE_AUTH_BASE_URL', 'http://localhost:8080');
    const { authBaseUrl } = await import('../../runtime');

    expect(authBaseUrl()).toBe('https://auth.example.test');
  });
});
