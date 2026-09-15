// World 화면 소유권 (S15P21A604-450, GitLab #139·#132) — 월드 위에는 한 번에 하나만 뜬다.
//
// 이 파일이 잠그는 것은 "판정이 한 곳에 있다" 는 사실이다. 두 store 를 각자 열면 겹치는 것이
// 원래 동작이었고(#139), 그래서 여는 길을 이 계층으로 모았다. 배타가 깨지면 여기서 먼저 터진다.
import { beforeEach, describe, expect, it } from 'vitest';
import { closeOverlay, getCurrentOverlay, openOverlay } from '../../../../../shared/types/overlay';
import {
  __resetGameClientUiForTests,
  getGameClientUiSnapshot,
  openBoothManagement,
  openGameMenu,
} from '../../gameClientUi';
import {
  closeTopScreen,
  getWorldScreen,
  openManagement,
  openMenu,
  openVisitorOverlay,
} from '../../worldScreen';

beforeEach(() => {
  closeOverlay();
  __resetGameClientUiForTests();
});

describe('getWorldScreen — 두 store 를 하나의 값으로', () => {
  it('아무것도 없으면 월드가 주인이다', () => {
    expect(getWorldScreen()).toBe('world');
  });

  it('각 레이어를 그 이름으로 판정한다', () => {
    openVisitorOverlay('LAPTOP', { boothId: 1 });
    expect(getWorldScreen()).toBe('visitor');

    openManagement();
    expect(getWorldScreen()).toBe('management');

    openMenu();
    expect(getWorldScreen()).toBe('menu');
  });

  it('배타 진입을 우회해 둘이 켜져도 판정이 흔들리지 않는다 — visitor 가 앞선다', () => {
    // store 를 직접 열어 배타 계층을 건너뛴 상태를 만든다(#139 가 보고한 그 상태)
    openOverlay('LAPTOP', { boothId: 1 });
    openBoothManagement();
    expect(getWorldScreen()).toBe('visitor');
  });

  it('management 가 menu 보다 앞선다', () => {
    openBoothManagement();
    openGameMenu(); // gameClientUi 자체 배타로 management 는 꺼진다
    expect(getWorldScreen()).toBe('menu');
  });
});

describe('배타 진입 — 여는 쪽이 나머지를 걷는다', () => {
  it('Visitor 위에 관리 화면을 열면 Visitor 가 닫힌다 (#139 회귀)', () => {
    openVisitorOverlay('LAPTOP', { boothId: 1 });
    openManagement();

    expect(getCurrentOverlay()).toBeNull();
    expect(getGameClientUiSnapshot().managementOverlay).toBe(true);
    expect(getWorldScreen()).toBe('management');
  });

  it('관리 화면 위에 Visitor 를 열면 관리 화면이 닫힌다', () => {
    openManagement();
    openVisitorOverlay('SURVEY', { boothId: 2 });

    expect(getGameClientUiSnapshot().managementOverlay).toBe(false);
    expect(getWorldScreen()).toBe('visitor');
  });

  it('메뉴 위에 Visitor 를 열면 메뉴가 닫힌다', () => {
    openMenu();
    openVisitorOverlay('AI_CHAT', { boothId: 3, agentId: 1 });

    expect(getGameClientUiSnapshot().gameMenu).toBe(false);
    expect(getWorldScreen()).toBe('visitor');
  });

  it('Visitor 를 연달아 열면 슬롯 하나로 유지된다 — F 연타에도 창은 하나다', () => {
    openVisitorOverlay('LAPTOP', { boothId: 1 });
    openVisitorOverlay('PROJECT', { boothId: 2 });

    expect(getCurrentOverlay()).toEqual({ type: 'PROJECT', payload: { boothId: 2 } });
    expect(getWorldScreen()).toBe('visitor');
  });

  it('같은 레이어를 다시 열어도 자기 자신을 닫지 않는다', () => {
    openManagement();
    openManagement();
    expect(getGameClientUiSnapshot().managementOverlay).toBe(true);
  });
});

describe('closeTopScreen — 현재 주인 하나만', () => {
  it('월드가 주인이면 아무것도 닫지 않고 false 를 낸다', () => {
    expect(closeTopScreen()).toBe(false);
    expect(getWorldScreen()).toBe('world');
  });

  it('떠 있는 것을 닫고 월드로 돌려준다', () => {
    for (const open of [() => openVisitorOverlay('LAPTOP', { boothId: 1 }), openManagement, openMenu]) {
      open();
      expect(closeTopScreen()).toBe(true);
      expect(getWorldScreen()).toBe('world');
    }
  });

  it('배타를 우회해 둘이 켜져 있으면 앞선 것부터 하나씩 닫는다', () => {
    openOverlay('LAPTOP', { boothId: 1 });
    openBoothManagement();

    expect(closeTopScreen()).toBe(true);
    expect(getWorldScreen()).toBe('management'); // 한 번에 하나만 닫는다
    expect(closeTopScreen()).toBe(true);
    expect(getWorldScreen()).toBe('world');
  });
});
