// @vitest-environment jsdom
// OverlayHost 가 GAME 요청을 실제 GameOverlay 로 연결하는지 검사한다 (S15P21A604-114, #56).
// GameOverlay 를 직접 렌더해서 검사하지 않는 것이 요점이다 — 그렇게 하면 어댑터는 통과하는데
// 호스트가 그것을 아무 데도 안 꽂은 상태를 못 잡는다. resolver·오버레이 어댑터는 이미 있었고
// 빠져 있던 것이 정확히 이 배선이었다(GameOverlay 의 비테스트 소비자 0).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { OverlayHost } from '../../OverlayHost';
import { closeOverlay, openOverlay } from '../../../../shared/types/overlay';

const BOOTH_ID = 3;
const CONFIG_ID = 7;
const PORTAL_PATH = `/api/v1/game-portals/${CONFIG_ID}`;
const NOT_READY_TEXT = '이 기능은 준비 중입니다.';

const jsonResponse = (body: unknown, status = 200): Response => new Response(
  JSON.stringify(body),
  { status, headers: { 'Content-Type': 'application/json' } },
);

let calls: string[];

// playable:false 로 응답한다 — 배선만 보려는 것이라 게임 런타임까지 끌고 들어갈 이유가 없다.
// 이 경로도 GameOverlay 가 그리는 화면이므로 "호스트가 GameOverlay 에 도달했는가"는 똑같이 증명된다.
const installFetch = () => {
  calls = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    if (url.includes(PORTAL_PATH)) {
      return jsonResponse({
        configId: CONFIG_ID,
        boothId: BOOTH_ID,
        playable: false,
        gameId: null,
        publishedVersion: null,
        unavailableReason: 'GAME_NOT_PUBLISHED',
      });
    }
    return jsonResponse({ code: 'UNKNOWN', message: 'unexpected', errors: [], warnings: [] }, 404);
  }));
};

beforeEach(() => {
  installFetch();
});

afterEach(() => {
  closeOverlay();
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('OverlayHost — GAME 배선', () => {
  it('GAME 요청이 오면 게임 포털을 조회한다', async () => {
    openOverlay('GAME', { boothId: BOOTH_ID, objectId: 'portal-1', configId: CONFIG_ID });
    render(<OverlayHost />);

    await waitFor(() => {
      expect(calls.filter((url) => url.includes(PORTAL_PATH))).toHaveLength(1);
    });
  });

  it('GAME 요청에 임시 "준비 중" 화면을 더 이상 보여주지 않는다', async () => {
    openOverlay('GAME', { boothId: BOOTH_ID, objectId: 'portal-1', configId: CONFIG_ID });
    render(<OverlayHost />);

    // 서버가 준 사유가 그대로 화면에 나온다 = GameOverlay 까지 도달했다는 뜻이다.
    await waitFor(() => {
      expect(screen.getByText('아직 게시되지 않은 게임입니다.')).toBeTruthy();
    });
    expect(screen.queryByText(NOT_READY_TEXT)).toBeNull();
  });

  it('LAPTOP 은 그대로 LaptopOverlay 로 간다 (회귀)', () => {
    openOverlay('LAPTOP', { boothId: BOOTH_ID, objectId: 'laptop-1', url: 'https://example.com' });
    render(<OverlayHost />);

    expect(screen.queryByText(NOT_READY_TEXT)).toBeNull();
    expect(calls.filter((url) => url.includes(PORTAL_PATH))).toHaveLength(0);
  });

  it('UI 가 아직 없는 타입은 준비 중 화면을 유지한다', () => {
    openOverlay('SURVEY', { boothId: BOOTH_ID, objectId: 'survey-1' });
    render(<OverlayHost />);

    expect(screen.getByText(NOT_READY_TEXT)).toBeTruthy();
  });
});
