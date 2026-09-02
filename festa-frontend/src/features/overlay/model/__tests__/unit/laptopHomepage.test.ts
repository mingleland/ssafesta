import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/booth/facadeApi.select', async () => {
  const mockApi = await import('../../../../../entities/booth/facadeApi.mock');
  return { facadeApi: mockApi };
});

import { __resetLaptopHomepageForTests, getLaptopHomepageSnapshot, loadLaptopHomepage } from '../../laptopHomepage';
import { __resetHomepageMockForTests, putHomepage } from '../../../../../entities/booth/homepageApi.mock';

beforeEach(() => {
  __resetLaptopHomepageForTests();
  __resetHomepageMockForTests();
});

describe('loadLaptopHomepage — URL 정본은 booth 조회 (016 C-01)', () => {
  it('등록된 부스는 valid — href·hostname 파싱', async () => {
    await loadLaptopHomepage(1);
    expect(getLaptopHomepageSnapshot()).toMatchObject({
      kind: 'valid',
      href: 'https://festa.example.com/',
      hostname: 'festa.example.com',
    });
  });

  it('미등록 부스는 no_url', async () => {
    await loadLaptopHomepage(2);
    expect(getLaptopHomepageSnapshot().kind).toBe('no_url');
  });

  it('등록 직후 재상호작용하면 새 URL 이 보인다 — 저장소가 정본', async () => {
    await putHomepage(2, 'https://new.example.com');
    await loadLaptopHomepage(2);
    expect(getLaptopHomepageSnapshot()).toMatchObject({ kind: 'valid', hostname: 'new.example.com' });
  });

  it('늦은 응답이 다른 부스 상태를 덮지 않는다', async () => {
    const first = loadLaptopHomepage(1);
    await loadLaptopHomepage(2);
    await first;
    expect(getLaptopHomepageSnapshot()).toMatchObject({ kind: 'no_url', boothId: 2 });
  });
});
