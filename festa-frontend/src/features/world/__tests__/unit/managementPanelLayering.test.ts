// 관리 상세 패널의 레이어 계약 (S15P21A604-755).
//
// 상세는 관리 화면의 **자식**이다. 별도 소유자를 만들면 ESC 한 번이 두 겹을 함께 걷고, 상세를
// 닫았을 때 관리 화면이 아니라 월드로 떨어진다.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  __resetGameClientUiForTests,
  getGameClientUiSnapshot,
} from '../../model/gameClientUi';
import {
  closeTopScreen,
  getWorldScreen,
  openManagement,
  openManagementDetail,
  openMenu,
  openVisitorOverlay,
} from '../../model/worldScreen';
import { closeOverlay } from '../../../../shared/types/overlay';

beforeEach(() => {
  __resetGameClientUiForTests();
  closeOverlay();
});
afterEach(() => {
  __resetGameClientUiForTests();
  closeOverlay();
});

describe('관리 상세 패널 레이어 (-755)', () => {
  it('상세가 떠도 화면 소유자는 여전히 management 다 — 새 소유자를 만들지 않는다', () => {
    openManagementDetail({ kind: 'project', boothId: 42 });

    expect(getWorldScreen()).toBe('management');
    expect(getGameClientUiSnapshot().managementOverlay).toBe(true);
  });

  it('ESC 는 상세 → 관리 → 월드 순으로 한 겹씩만 닫는다', () => {
    openManagementDetail({ kind: 'survey', boothId: 42 });

    expect(closeTopScreen()).toBe(true);
    expect(getGameClientUiSnapshot().managementPanel).toBeNull();
    // 관리 화면으로 돌아왔다 — 월드로 떨어지지 않는다
    expect(getWorldScreen()).toBe('management');

    expect(closeTopScreen()).toBe(true);
    expect(getWorldScreen()).toBe('world');

    expect(closeTopScreen()).toBe(false);
  });

  it('상세는 한 번에 하나만 뜬다', () => {
    openManagementDetail({ kind: 'project', boothId: 42 });
    openManagementDetail({ kind: 'consultation', boothId: 42 });

    expect(getGameClientUiSnapshot().managementPanel).toEqual({ kind: 'consultation', boothId: 42 });
  });

  it('관리 화면을 닫으면 자식도 함께 사라진다 — 부모 없는 상세를 남기지 않는다', () => {
    openManagementDetail({ kind: 'ai-agent', boothId: 42 });

    openManagement();

    expect(getGameClientUiSnapshot().managementPanel).toBeNull();
  });

  it('다른 레이어가 열리면 상세도 걷힌다 — 배타는 그대로다', () => {
    openManagementDetail({ kind: 'studio', boothId: 42 });
    openMenu();
    expect(getGameClientUiSnapshot().managementPanel).toBeNull();
    expect(getWorldScreen()).toBe('menu');

    openManagementDetail({ kind: 'studio', boothId: 42 });
    openVisitorOverlay('WORLD_GUIDE', null);
    expect(getGameClientUiSnapshot().managementPanel).toBeNull();
    expect(getWorldScreen()).toBe('visitor');
  });
});
