import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/booth/facadeApi.select', async () => {
  const mockApi = await import('../../../../../entities/booth/facadeApi.mock');
  return { facadeApi: mockApi };
});

import {
  __resetLaptopHomepageForTests,
  getLaptopHomepageSnapshot,
  loadLaptopHomepage,
  resolveLaptopHomepage,
} from '../../laptopHomepage';
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

// 판정이 순수 함수라 `window` 를 스텁하지 않는다 — 현재 오리진을 인자로 넘긴다 (S15P21A604-946).
describe('resolveLaptopHomepage — 어디에 띄울 수 있는가', () => {
  const here = { currentOrigin: 'https://demo.ssafesta.world' };

  it('http 주소는 external_only(insecure) — href 는 그대로 남아 새 탭이 연다', () => {
    expect(resolveLaptopHomepage('http://team.example.com/hello', here)).toEqual({
      kind: 'external_only',
      reason: 'insecure',
      href: 'http://team.example.com/hello',
      hostname: 'team.example.com',
    });
  });

  it('현재 오리진과 같으면 external_only(self_origin) — sandbox 이탈 경로를 만들지 않는다', () => {
    expect(resolveLaptopHomepage('https://demo.ssafesta.world/app/world', here)).toMatchObject({
      kind: 'external_only',
      reason: 'self_origin',
    });
  });

  it('외부 https 는 valid', () => {
    expect(resolveLaptopHomepage('https://team.example.com/', here)).toMatchObject({
      kind: 'valid',
      hostname: 'team.example.com',
    });
  });

  it('http/https 가 아니면 invalid — 새 탭으로도 넘기지 않는다', () => {
    expect(resolveLaptopHomepage('javascript:alert(1)', here).kind).toBe('invalid');
  });
});
