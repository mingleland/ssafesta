import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/booth/facadeApi.select', async () => {
  const mockApi = await import('../../../../../entities/booth/facadeApi.mock');
  return { facadeApi: mockApi };
});
vi.mock('../../../../../entities/booth/homepageApi.select', async () => {
  const mockApi = await import('../../../../../entities/booth/homepageApi.mock');
  return { homepageApi: mockApi };
});

import {
  __resetHomepageFormForTests,
  clearHomepage,
  getHomepageFormSnapshot,
  loadHomepageForm,
  saveHomepage,
  setHomepageValue,
} from '../../homepage';
import { __resetHomepageMockForTests, getMockHomepageUrl } from '../../../../../entities/booth/homepageApi.mock';

beforeEach(() => {
  __resetHomepageFormForTests();
  __resetHomepageMockForTests();
});

describe('homepage 등록 폼', () => {
  it('기존 등록값이 prefill 된다', async () => {
    await loadHomepageForm(1);
    const s = getHomepageFormSnapshot();
    expect(s.status).toBe('ready');
    expect(s.saved).toBe('https://festa.example.com');
    expect(s.value).toBe('https://festa.example.com');
  });

  it('저장 성공 — 저장소 반영·success', async () => {
    await loadHomepageForm(2);
    setHomepageValue('https://team.example.com');
    await saveHomepage();
    expect(getHomepageFormSnapshot()).toMatchObject({ saved: 'https://team.example.com', save: { phase: 'success' } });
    expect(getMockHomepageUrl(2)).toBe('https://team.example.com');
  });

  it('형식 위반은 invalid — 서버 문구를 그대로 노출', async () => {
    await loadHomepageForm(2);
    setHomepageValue('ftp://bad.example.com');
    await saveHomepage();
    const s = getHomepageFormSnapshot();
    expect(s.save.phase).toBe('error');
    expect(s.save.errorKind).toBe('invalid');
    expect(s.save.errorMessage).toContain('http');
  });

  it('빈 값 저장은 no-op — 해제는 clearHomepage 로만 (PresenceField 의도 구분)', async () => {
    await loadHomepageForm(1);
    setHomepageValue('  ');
    await saveHomepage();
    expect(getHomepageFormSnapshot().save.phase).toBe('idle');
    expect(getMockHomepageUrl(1)).toBe('https://festa.example.com');
    await clearHomepage();
    expect(getHomepageFormSnapshot()).toMatchObject({ saved: null, value: '', save: { phase: 'success' } });
    expect(getMockHomepageUrl(1)).toBeNull();
  });
});
