import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/project/api.select', async () => {
  const mockApi = await import('../../../../../entities/project/api.mock');
  return { projectApi: mockApi };
});

import { __resetExhibitionForTests, getExhibitionSnapshot, loadExhibition } from '../../exhibition';
import { __resetProjectMockForTests } from '../../../../../entities/project/api.mock';

beforeEach(() => {
  __resetExhibitionForTests();
  __resetProjectMockForTests();
});

describe('loadExhibition', () => {
  it('전시 1건 — video 는 parseVideoEmbed 판정 결과, like 는 표시 전용', async () => {
    await loadExhibition(1);
    const s = getExhibitionSnapshot();
    expect(s.status).toBe('ready');
    expect(s.projects).toHaveLength(1);
    const p = s.projects[0];
    expect(p.video).toMatchObject({ kind: 'EMBED', videoId: 'dQw4w9WgXcQ' });
    expect(p.like).toEqual({ count: 7, likedByMe: false, canToggle: false });
    expect(p.links.deployUrl).toBe('https://festa.example.com');
  });

  it('0건은 empty — 오류가 아니다', async () => {
    await loadExhibition(2);
    expect(getExhibitionSnapshot().status).toBe('empty');
  });

  it('조회 실패는 error', async () => {
    await loadExhibition(99);
    expect(getExhibitionSnapshot().status).toBe('error');
  });

  it('늦게 도착한 응답이 다른 부스 화면을 덮지 않는다', async () => {
    const first = loadExhibition(1);
    await loadExhibition(2);
    await first;
    const s = getExhibitionSnapshot();
    expect(s.boothId).toBe(2);
    expect(s.status).toBe('empty');
  });
});
