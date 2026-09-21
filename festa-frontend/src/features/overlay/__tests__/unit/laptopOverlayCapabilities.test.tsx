// @vitest-environment jsdom
// 노트북 오버레이의 **iframe 능력 경계** 회귀 가드 (S15P21A604-946).
//
// 이 파일이 있는 이유: sandbox·allow 는 한 토큰만 늘어도 외부 사이트에 주는 권한이 바뀌는데 그
// 변화가 화면에는 아무 흔적도 남기지 않는다. 연 것과 **열지 않기로 한 것**을 같은 자리에 적어 둬야
// 다음 사람이 "다 열면 되지 않나" 로 되돌리지 않는다.
//
// 문자열 전체 일치로 단언하지 않는다 — 토큰 순서를 바꾼 무해한 편집이 실패로 잡히면 테스트가
// 계약이 아니라 서식을 지키게 된다. 포함/제외만 본다.
//
// 서버 조회(`loadLaptopHomepage`)만 잠재운다. 상태 저장소와 구독은 진짜를 쓴다 — 화면이 상태를
// 어떻게 읽는지가 이 테스트의 대상이다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('../../model/laptopHomepage', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../model/laptopHomepage')>()),
  loadLaptopHomepage: vi.fn(async () => {}),
  resetLaptopHomepage: vi.fn(() => {}),
}));

import { LaptopOverlay } from '../../LaptopOverlay';
import {
  __pushLaptopHomepageForTests,
  __resetLaptopHomepageForTests,
} from '../../model/laptopHomepage';

beforeEach(() => __resetLaptopHomepageForTests());
afterEach(cleanup);

const PAYLOAD = { boothId: 7, objectId: 'laptop-1' };

function iframeOf(): HTMLIFrameElement | null {
  return document.querySelector('iframe.laptop-iframe');
}

describe('LaptopOverlay — iframe 능력 경계', () => {
  it('valid 는 iframe 을 띄우고, 연 권한과 닫아 둔 권한이 같이 고정된다', () => {
    __pushLaptopHomepageForTests({
      kind: 'valid', boothId: 7, href: 'https://team.example.com/', hostname: 'team.example.com',
    });
    render(<LaptopOverlay payload={PAYLOAD} />);

    const frame = iframeOf();
    expect(frame).not.toBeNull();

    const sandbox = frame!.getAttribute('sandbox') ?? '';
    // 팝업이 부모 sandbox 를 상속해 깨지던 OAuth/SSO 를 푸는 토큰이다
    expect(sandbox).toContain('allow-popups-to-escape-sandbox');
    // top 이동을 열면 프레임 안 사이트가 탭 전체를 밖으로 보내 Unity 월드가 죽는다
    expect(sandbox).not.toContain('allow-top-navigation');

    const allow = frame!.getAttribute('allow') ?? '';
    expect(allow).toContain('clipboard-write');
    for (const denied of ['clipboard-read', 'camera', 'microphone', 'geolocation']) {
      expect(allow).not.toContain(denied);
    }
  });

  it('external_only 는 iframe 을 걸지 않고 새 탭으로 보낸다', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    __pushLaptopHomepageForTests({
      kind: 'external_only', boothId: 7, href: 'http://team.example.com/',
      hostname: 'team.example.com', reason: 'insecure',
    });
    render(<LaptopOverlay payload={PAYLOAD} />);

    expect(iframeOf()).toBeNull();

    screen.getByRole('button', { name: '새 탭에서 열기' }).click();
    expect(open).toHaveBeenCalledWith('http://team.example.com/', '_blank', 'noopener,noreferrer');
    open.mockRestore();
  });
});

