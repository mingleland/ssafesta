import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/project/api.select', async () => {
  const mockApi = await import('../../../../../entities/project/api.mock');
  return { projectApi: mockApi };
});

import { __resetExhibitionForTests, getExhibitionSnapshot, loadExhibition, toggleLike } from '../../exhibition';
import { __failNextLikeForTests, __resetProjectMockForTests } from '../../../../../entities/project/api.mock';
import { __resetSessionForTests, setGuestSession, setMemberSession } from '../../../../auth/model/session';

beforeEach(() => {
  __resetExhibitionForTests();
  __resetProjectMockForTests();
  __resetSessionForTests();
});

const FUTURE = () => new Date(Date.now() + 60_000).toISOString();
const like = () => getExhibitionSnapshot().projects[0].like;

describe('loadExhibition', () => {
  it('전시 1건 — video 는 parseVideoEmbed 판정 결과, 비로그인은 like 표시 전용', async () => {
    await loadExhibition(1);
    const s = getExhibitionSnapshot();
    expect(s.status).toBe('ready');
    expect(s.projects).toHaveLength(1);
    const p = s.projects[0];
    expect(p.video).toMatchObject({ kind: 'EMBED', videoId: 'dQw4w9WgXcQ' });
    expect(p.like).toEqual({ count: 7, likedByMe: false, canToggle: false, pending: false, error: false });
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

  it('회원은 canToggle — 누르면 낙관 반영 후 응답 두 값으로 확정, 다시 누르면 DELETE (#122)', async () => {
    setMemberSession('at', FUTURE());
    await loadExhibition(1);
    expect(like().canToggle).toBe(true);
    const first = toggleLike(10);
    expect(like()).toMatchObject({ likedByMe: true, count: 8, pending: true }); // 낙관 반영
    await first;
    expect(like()).toMatchObject({ likedByMe: true, count: 8, pending: false, error: false });
    await toggleLike(10);
    expect(like()).toMatchObject({ likedByMe: false, count: 7, pending: false });
  });

  it('pending 중 재클릭은 무시된다 — 이중 요청 방지', async () => {
    setMemberSession('at', FUTURE());
    await loadExhibition(1);
    const first = toggleLike(10);
    await toggleLike(10); // pending — no-op (아니면 DELETE 로 취소돼 count 7 로 돌아간다)
    await first;
    expect(like()).toMatchObject({ likedByMe: true, count: 8 });
  });

  it('실패하면 되돌리고 error 를 켠다 — 재시도 가능', async () => {
    setMemberSession('at', FUTURE());
    await loadExhibition(1);
    __failNextLikeForTests();
    await toggleLike(10);
    expect(like()).toMatchObject({ likedByMe: false, count: 7, pending: false, error: true });
    await toggleLike(10); // 멱등이라 재시도 안전
    expect(like()).toMatchObject({ likedByMe: true, count: 8, error: false });
  });

  it('게스트는 canToggle=false — toggleLike 가 서버를 부르지 않는다 (403 MEMBER_ONLY 회피)', async () => {
    setGuestSession('at', FUTURE());
    await loadExhibition(1);
    expect(like().canToggle).toBe(false);
    await toggleLike(10);
    expect(like()).toMatchObject({ likedByMe: false, count: 7 });
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
