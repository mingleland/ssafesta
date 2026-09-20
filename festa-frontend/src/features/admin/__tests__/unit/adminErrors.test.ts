import { afterEach, describe, expect, it, vi } from 'vitest';
import { describeAdminError } from '../../model/adminErrors';

const api = (code: string, status: number) => ({ code, message: '서버 문구', status, requestId: 'req-7', errors: [], warnings: [] });

afterEach(() => vi.restoreAllMocks());

describe('describeAdminError', () => {
  it('운영 안내가 있는 코드는 raw code 대신 그 문장을 쓰고, 4xx 는 재시도를 권하지 않는다', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const v = describeAdminError(api('ADMIN_LAST_ONE', 409));
    expect(v.title).toMatch(/마지막 관리자/);
    expect(v.message).toMatch(/다른 관리자를 승격/);
    expect(v.retryable).toBe(false);
    expect(describeAdminError(api('IDEMPOTENCY_CONFLICT', 409)).message).toMatch(/새로운 조정을 시작/);
    expect(describeAdminError(api('MASTER_PROTECTED', 403)).title).toMatch(/보호된 계정/);
  });

  it('모르는 코드는 서버 문구를 쓰고, 5xx·네트워크 실패는 재시도 가능하다', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(describeAdminError(api('SOMETHING_NEW', 500))).toMatchObject({ message: '서버 문구', retryable: true });
    expect(describeAdminError(new TypeError('Failed to fetch'))).toMatchObject({ code: 'UNKNOWN', retryable: true });
  });

  it('진단 로그에 code·status·requestId 가 남는다', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    describeAdminError(api('FORBIDDEN', 403));
    expect(String(warn.mock.calls[0][0])).toMatch(/FORBIDDEN \(403\) requestId=req-7/);
  });
});

