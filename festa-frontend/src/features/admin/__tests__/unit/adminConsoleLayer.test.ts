// 콘솔이 월드 레이어 체계에 제대로 끼는지 — 오버레이로 바꾸면서 새로 생긴 계약이다.
// 여기가 깨지면 ESC 가 콘솔을 못 닫거나, 콘솔 위에 Game Menu 가 겹쳐 뜬다.
import { beforeEach, describe, expect, it } from 'vitest';
import {
  closeTopScreen,
  getWorldScreen,
  openManagement,
  openMenu,
  openMenuPanelScreen,
  openVisitorOverlay,
} from '../../../world/model/worldScreen';
import { __resetGameClientUiForTests, getGameClientUiSnapshot } from '../../../world/model/gameClientUi';
import { closeOverlay } from '../../../../shared/types/overlay';
import {
  __resetAdminConsoleForTests,
  getAdminConsoleSnapshot,
  openAdminSection,
  resetAdminConsole,
  selectAdminUser,
  selectEventResponse,
  selectEventSurvey,
} from '../../model/consoleState';

beforeEach(() => {
  __resetGameClientUiForTests();
  closeOverlay();
  __resetAdminConsoleForTests();
});

describe('콘솔 레이어', () => {
  it('ESC 메뉴의 자식이다 — 닫으면 월드가 아니라 메뉴로 돌아간다', () => {
    openMenu();
    openMenuPanelScreen('admin');
    expect(getWorldScreen()).toBe('menuPanel');
    expect(getGameClientUiSnapshot().menuPanel).toBe('admin');

    expect(closeTopScreen()).toBe(true);
    expect(getWorldScreen()).toBe('menu');
  });

  it('다른 레이어와 겹치지 않는다', () => {
    openMenuPanelScreen('admin');
    openManagement();
    expect(getGameClientUiSnapshot().menuPanel).toBeNull();

    openMenuPanelScreen('admin');
    openVisitorOverlay('LAPTOP', { boothId: 1 });
    expect(getWorldScreen()).toBe('visitor');
    expect(getGameClientUiSnapshot().menuPanel).toBeNull();
  });
});

describe('콘솔 상태', () => {
  it('섹션을 옮겨도 고른 회원은 남는다 — 회원에서 지갑으로 이어 보는 것이 주된 동선이다', () => {
    selectAdminUser(5);
    openAdminSection('wallets');
    expect(getAdminConsoleSnapshot()).toMatchObject({ section: 'wallets', userId: 5 });
  });

  it('섹션을 옮기면 열려 있던 응답 상세는 비운다', () => {
    selectEventSurvey('SSAFESTA_2026');
    selectEventResponse(904);
    openAdminSection('overview');
    expect(getAdminConsoleSnapshot().responseId).toBeNull();
    expect(getAdminConsoleSnapshot().surveyKey).toBe('SSAFESTA_2026');
  });

  it('설문을 바꾸면 그 설문의 응답 상세도 함께 비운다', () => {
    selectEventSurvey('A');
    selectEventResponse(1);
    selectEventSurvey('B');
    expect(getAdminConsoleSnapshot().responseId).toBeNull();
  });

  it('닫으면 처음 상태로 돌아간다', () => {
    openAdminSection('shop');
    selectAdminUser(3);
    resetAdminConsole();
    expect(getAdminConsoleSnapshot()).toEqual({ section: 'overview', userId: null, surveyKey: null, responseId: null });
  });
});
