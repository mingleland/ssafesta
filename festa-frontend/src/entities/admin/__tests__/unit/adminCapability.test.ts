// 관리자 판정의 근거 — BE 가 /users/me 에 admin 칸을 주기 전까지는 관리자 API 의 응답 자체가 근거다.
// 이 판정이 틀리면 관리자가 콘솔에서 막히거나(가장 잦은 사고) 비관리자에게 콘솔이 열린다.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const apiMock = vi.fn();
vi.mock('../../../../shared/api/client', async () => {
  const actual = await vi.importActual<typeof import('../../../../shared/api/client')>('../../../../shared/api/client');
  return { ...actual, api: (path: string, init?: unknown) => apiMock(path, init) };
});

const { adminApi } = await import('../../api');

const me = { userId: 4, nickname: '구글 황덕', status: 'ACTIVE', providers: ['GOOGLE'], avatarCode: null };
const forbidden = { code: 'FORBIDDEN', message: '권한이 없습니다.', status: 403, errors: [], warnings: [] };

beforeEach(() => apiMock.mockReset());

describe('getCapability', () => {
  it('/users/me 가 admin 을 실어 주면 그 값을 쓰고 관리자 목록을 부르지 않는다', async () => {
    apiMock.mockResolvedValueOnce({ ...me, admin: true, master: true });
    expect(await adminApi.getCapability()).toEqual({ admin: true, master: true });
    expect(apiMock).toHaveBeenCalledTimes(1);
  });

  it('칸이 없으면 /admin/admins 200 을 관리자 판정의 근거로 삼고 master 는 내 행에서 읽는다', async () => {
    apiMock
      .mockResolvedValueOnce(me)
      .mockResolvedValueOnce([{ userId: 4, nickname: '구글 황덕', master: true }, { userId: 2, nickname: '이정헌', master: false }]);
    expect(await adminApi.getCapability()).toEqual({ admin: true, master: true });
    expect(apiMock.mock.calls[1][0]).toBe('/api/v1/admin/admins');
  });

  it('목록에 내가 없어도 200 을 받은 사실이 판정이다 — master 만 false 다', async () => {
    apiMock.mockResolvedValueOnce(me).mockResolvedValueOnce([{ userId: 2, nickname: '이정헌', master: false }]);
    expect(await adminApi.getCapability()).toEqual({ admin: true, master: false });
  });

  it('403 은 "관리자가 아니다" 라는 정상 답이다', async () => {
    apiMock.mockResolvedValueOnce(me).mockRejectedValueOnce(forbidden);
    expect(await adminApi.getCapability()).toEqual({ admin: false, master: false });
  });

  it('403 이 아닌 실패는 삼키지 않는다 — 조용히 false 로 접으면 관리자가 권한 없음 화면을 본다', async () => {
    apiMock.mockResolvedValueOnce(me).mockRejectedValueOnce({ code: 'UNKNOWN', message: 'x', status: 500, errors: [], warnings: [] });
    await expect(adminApi.getCapability()).rejects.toMatchObject({ status: 500 });
  });

  it('admin: false 를 명시로 받으면 목록을 묻지 않고 그대로 막는다', async () => {
    apiMock.mockResolvedValueOnce({ ...me, admin: false, master: false });
    expect(await adminApi.getCapability()).toEqual({ admin: false, master: false });
    expect(apiMock).toHaveBeenCalledTimes(1);
  });
});

