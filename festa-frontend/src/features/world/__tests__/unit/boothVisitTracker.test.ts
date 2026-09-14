import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetSessionForTests, setGuestSession, setMemberSession } from '../../../auth/model/session';
import {
  __resetBoothVisitTrackerForTests,
  applyWorldContextChange,
  discardPendingVisit,
  getPendingVisit,
  setBoothVisitSink,
  type BoothVisitEdge,
} from '../../model/boothVisitTracker';

const AT = '2026-09-14T00:00:00.000Z';
const now = () => AT;

let edges: BoothVisitEdge[];
let warn: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  __resetBoothVisitTrackerForTests();
  __resetSessionForTests();
  edges = [];
  setBoothVisitSink((edge) => edges.push(edge));
  warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(() => {
  warn.mockRestore();
});

function member() {
  setMemberSession('at', '2026-12-31T00:00:00.000Z');
}

describe('boothVisitTracker', () => {
  it('입장과 퇴장을 각각 한 번씩 잡는다', () => {
    member();
    applyWorldContextChange({ insideBooth: true, boothId: 3 }, now);
    applyWorldContextChange({ insideBooth: false, boothId: null }, now);

    expect(edges.map((e) => [e.kind, e.boothId])).toEqual([
      ['enter', 3],
      ['exit', 3],
    ]);
    expect(getPendingVisit()).toBeNull();
  });

  it('같은 값이 반복돼도 경계를 만들지 않는다 — Unity 가 0.35초 스윕으로 같은 상태를 다시 보낸다', () => {
    applyWorldContextChange({ insideBooth: true, boothId: 3 }, now);
    applyWorldContextChange({ insideBooth: true, boothId: 3 }, now);
    applyWorldContextChange({ insideBooth: true, boothId: 3 }, now);

    expect(edges).toHaveLength(1);
    expect(getPendingVisit()).toEqual({ boothId: 3, enteredAt: AT });
  });

  it('부스 밖을 거치지 않은 A→B 전환을 A 퇴장 + B 진입으로 가르고 경고를 남긴다', () => {
    member();
    applyWorldContextChange({ insideBooth: true, boothId: 3 }, now);
    edges = [];

    applyWorldContextChange({ insideBooth: true, boothId: 7 }, now);

    expect(edges.map((e) => [e.kind, e.boothId])).toEqual([
      ['exit', 3],
      ['enter', 7],
    ]);
    expect(getPendingVisit()).toEqual({ boothId: 7, enteredAt: AT });
    expect(warn).toHaveBeenCalled();
  });

  it('boothId 없이 들어오면 번호를 지어내지 않고 null 로 열며 경고를 남긴다', () => {
    applyWorldContextChange({ insideBooth: true, boothId: null }, now);

    expect(edges).toHaveLength(1);
    expect(edges[0].boothId).toBeNull();
    expect(getPendingVisit()).toEqual({ boothId: null, enteredAt: AT });
    expect(warn).toHaveBeenCalled();
  });

  it('Unity 재부팅으로 pending 을 버리면 뒤이은 OUTSIDE 신호가 퇴장으로 읽히지 않는다', () => {
    applyWorldContextChange({ insideBooth: true, boothId: 3 }, now);
    edges = [];

    discardPendingVisit();
    applyWorldContextChange({ insideBooth: false, boothId: null }, now);

    expect(edges).toHaveLength(0);
    expect(getPendingVisit()).toBeNull();
  });

  it('게스트 퇴장은 보내지 않는다 — 서버 소유 판정이 403 을 준다. 입장은 게스트도 보낸다', () => {
    setGuestSession('at', '2026-12-31T00:00:00.000Z');

    applyWorldContextChange({ insideBooth: true, boothId: 3 }, now);
    applyWorldContextChange({ insideBooth: false, boothId: null }, now);

    expect(edges.map((e) => [e.kind, e.sendable])).toEqual([
      ['enter', true],
      ['exit', false],
    ]);
  });

  it('식별자를 만들어 내지 않는다 — visitId 는 서버만 발급한다', () => {
    member();
    applyWorldContextChange({ insideBooth: true, boothId: 3 }, now);
    applyWorldContextChange({ insideBooth: false, boothId: null }, now);

    for (const edge of edges) {
      expect(Object.keys(edge).sort()).toEqual(['at', 'boothId', 'kind', 'sendable']);
    }
    expect(Object.keys(getPendingVisit() ?? {})).toEqual([]);
  });
});
