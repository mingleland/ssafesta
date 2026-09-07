// 개발자 진입이 토큰을 어디서 얻는지 (S15P21A604-467).
//
// 잠그는 것은 둘뿐이다 — 나머지는 `IS_DEV_ENTRY` 처럼 빌드 시 확정돼 테스트에서 갈아끼울 수 없다.
//   1. mock 모드는 네트워크를 타지 않는다
//   2. 실 BE 모드에서 refresh 가 실패하면 **세션을 만들지 않고 던진다**
//
// 2번이 이 회차 결함의 본질이다. 예전에는 실패해도 `'dev-entry'` 표식으로 회원 세션을 만들었고,
// 그 세션이 첫 요청의 401 로 지워지면서 로그인 화면으로 조용히 되돌아갔다 — 이유가 어디에도
// 남지 않는 실패였다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../entities/auth/api', () => ({ refresh: vi.fn() }));
vi.mock('../../../auth/model/session', () => ({ setMemberSession: vi.fn() }));

const authApi = await import('../../../../entities/auth/api');
const session = await import('../../../auth/model/session');
const { enterAsDeveloper } = await import('../../model/devEntry');

const useMock = (value: string) => vi.stubEnv('VITE_USE_MOCK', value);

beforeEach(() => {
  vi.mocked(authApi.refresh).mockReset();
  vi.mocked(session.setMemberSession).mockReset();
});

afterEach(() => vi.unstubAllEnvs());

describe('enterAsDeveloper', () => {
  it('mock 모드에서는 refresh 를 부르지 않는다 — 표식 토큰이면 충분하다', async () => {
    useMock('true');
    await enterAsDeveloper();
    expect(authApi.refresh).not.toHaveBeenCalled();
    expect(vi.mocked(session.setMemberSession).mock.calls[0][0]).toBe('dev-entry');
  });

  it('실 BE 모드에서는 refresh 가 준 진짜 토큰으로 세션을 만든다', async () => {
    useMock('false');
    vi.mocked(authApi.refresh).mockResolvedValue({ accessToken: 'real.jwt', expiresAt: '2026-01-01T00:00:00Z' });
    await enterAsDeveloper();
    expect(session.setMemberSession).toHaveBeenCalledWith('real.jwt', '2026-01-01T00:00:00Z');
  });

  it('실 BE 모드에서 refresh 가 실패하면 세션을 만들지 않고 던진다', async () => {
    useMock('false');
    vi.mocked(authApi.refresh).mockRejectedValue(new Error('INVALID_MEMBER_TOKEN'));
    await expect(enterAsDeveloper()).rejects.toThrow('INVALID_MEMBER_TOKEN');
    expect(session.setMemberSession).not.toHaveBeenCalled();
  });
});
