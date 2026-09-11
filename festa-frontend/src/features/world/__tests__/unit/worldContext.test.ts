// 월드 컨텍스트 store (S15P21A604-627, GitLab #174).
//
// 가장 중요한 경계는 **밖일 때 boothId 가 남지 않는가** 다. Unity 는 밖일 때 boothId 키 자체를
// 보내지 않는데(0 을 "0번 부스" 로 읽지 않으려고), 받는 쪽이 이전 값을 들고 있으면 "방금 나온
// 부스" 와 "지금 있는 부스" 가 구분되지 않는다.
import { afterEach, describe, expect, it } from 'vitest';
import {
  __resetWorldContextForTests,
  applyBoothContext,
  getWorldContext,
  resetWorldContext,
  subscribeWorldContext,
} from '../../model/worldContext';

afterEach(() => {
  __resetWorldContextForTests();
});

describe('부스 안/밖', () => {
  it('신호가 오기 전에는 밖이다 — Unity 가 아직 안 보내도 예전과 똑같이 동작한다', () => {
    expect(getWorldContext()).toEqual({ insideBooth: false, boothId: null });
  });

  it('안에 들어가면 번호까지 담는다', () => {
    applyBoothContext(true, 3);
    expect(getWorldContext()).toEqual({ insideBooth: true, boothId: 3 });
  });

  it('나오면 번호를 비운다 — 밖인데 남아 있으면 "방금 나온 부스"와 구분되지 않는다', () => {
    applyBoothContext(true, 3);
    applyBoothContext(false);
    expect(getWorldContext()).toEqual({ insideBooth: false, boothId: null });
  });

  it('밖 payload 에 boothId 가 실려 와도 담지 않는다 — 계약상 오지 않지만 와도 무시한다', () => {
    applyBoothContext(false, 7);
    expect(getWorldContext().boothId).toBeNull();
  });

  it('다른 부스로 옮기면 번호가 따라간다', () => {
    applyBoothContext(true, 3);
    applyBoothContext(true, 11);
    expect(getWorldContext()).toEqual({ insideBooth: true, boothId: 11 });
  });
});

describe('구독', () => {
  it('값이 바뀔 때만 알린다 — 같은 값이 또 와도 다시 그리지 않는다', () => {
    let calls = 0;
    const unsubscribe = subscribeWorldContext(() => { calls += 1; });

    applyBoothContext(true, 3);
    applyBoothContext(true, 3); // Unity 가 같은 값을 또 보내도
    expect(calls).toBe(1);

    applyBoothContext(false);
    expect(calls).toBe(2);
    unsubscribe();
  });

  it('변화가 없으면 같은 참조를 돌려준다 — useSyncExternalStore 가 무한 루프에 빠지지 않는다', () => {
    const first = getWorldContext();
    applyBoothContext(false); // 이미 밖이다
    expect(getWorldContext()).toBe(first);
  });
});

describe('인스턴스 리셋', () => {
  it('새 인스턴스에는 컨텍스트가 없다 — 유령 버튼을 막는다', () => {
    applyBoothContext(true, 3);
    resetWorldContext();
    expect(getWorldContext()).toEqual({ insideBooth: false, boothId: null });
  });
});
