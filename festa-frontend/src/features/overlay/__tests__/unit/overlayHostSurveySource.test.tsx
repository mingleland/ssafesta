// @vitest-environment jsdom
// 부스 설문과 이벤트 설문이 **한 OverlayType 을 공유하는지** (S15P21A604-608).
//
// OverlayHost 를 통해 본다. SurveyOverlay 를 직접 렌더하면 "화면은 되는데 호스트가 이벤트
// payload 를 아무 데도 안 꽂은" 상태를 못 잡는다 — GAME 배선에서 겪은 것과 같은 함정이다.
//
// `EVENT_SURVEY` 같은 두 번째 타입이 생기면 이 파일 중 하나가 red 가 된다. 그것이 목적이다:
// 화면·상태 기계가 같은 한 진입 열쇠 차이로 타입을 늘리지 않는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { OverlayHost } from '../../OverlayHost';
import { closeOverlay, openOverlay } from '../../../../shared/types/overlay';

vi.mock('../../../../entities/survey/api.select', async () => {
  const { surveyMockPort } = await import('../../../../entities/survey/api.mock');
  return { surveyApi: surveyMockPort };
});

const { MOCK_BOOTH_NORMAL, MOCK_EVENT_SURVEY_KEY, __resetSurveyMockForTests } = await import(
  '../../../../entities/survey/api.mock'
);
const { __resetSurveyRunForTests } = await import('../../../survey/model/run');

beforeEach(() => {
  __resetSurveyRunForTests();
  __resetSurveyMockForTests();
});

afterEach(() => {
  closeOverlay();
  cleanup();
});

describe('SURVEY 하나가 두 source 를 받는다', () => {
  it('부스 payload — 문항이 그려진다', async () => {
    render(<OverlayHost />);
    openOverlay('SURVEY', { kind: 'booth', boothId: MOCK_BOOTH_NORMAL });

    await waitFor(() => expect(screen.getByRole('button', { name: '제출하기' })).toBeTruthy());
  });

  it('이벤트 payload — 같은 화면이 그려진다. 새 OverlayType 을 거치지 않는다', async () => {
    render(<OverlayHost />);
    openOverlay('SURVEY', { kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY });

    await waitFor(() => expect(screen.getByRole('button', { name: '제출하기' })).toBeTruthy());
    // 준비 중 fallback 은 모르는 타입이 왔을 때의 화면이다 — 그것이 뜨면 배선이 없다는 뜻이다
    expect(screen.queryByText('이 기능은 준비 중입니다.')).toBeNull();
  });
});
