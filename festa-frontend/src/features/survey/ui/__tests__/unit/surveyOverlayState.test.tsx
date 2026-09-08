// @vitest-environment jsdom
// S15P21A604-541 회귀 — Survey 오버레이의 상태·오류 표시.
//
// 네 건 다 2026-09-08 FE 전체 회귀(-538)에서 실 서버·실 브라우저로 관측된 것이고,
// 근거는 docs/LJH/verify/fe-full-regression-0908.md §2 다.
//
// **각 테스트는 고친 코드를 되돌리면 red 가 되는 것을 확인하고 넣었다** — -531 C-1 에서
// "통과하는 테스트" 와 "잡는 테스트" 가 다르다는 것을 겪었기 때문이다.
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

vi.mock('../../../../../entities/survey/api.select', async () => {
  const { surveyMockPort } = await import('../../../../../entities/survey/api.mock');
  return { surveyApi: surveyMockPort };
});

import {
  MOCK_BOOTH_CLOSED_REWARDED,
  MOCK_BOOTH_REWARDED,
  __resetSurveyMockForTests,
} from '../../../../../entities/survey/api.mock';
import { __resetSurveyRunForTests, getSurveyRunSnapshot, setAnswer, submitSurveyRun } from '../../../model/run';
import { SurveyOverlay } from '../../SurveyOverlay';

const REWARD_NOTE = /참여하면 \d+ 코인을 받습니다/;

beforeEach(() => {
  cleanup();
  __resetSurveyRunForTests();
  __resetSurveyMockForTests();
});

describe('마감된 설문 (S15P21A604-541 F-2·F-3)', () => {
  it('문항을 남긴다 — 계약 §5 "무엇을 물었는지는 남는다"', async () => {
    render(<SurveyOverlay payload={{ boothId: MOCK_BOOTH_CLOSED_REWARDED }} />);

    await screen.findByText('마감된 설문입니다');

    // 서버는 closed 에도 문항을 싣는다. 화면이 그것을 버리면 방문자는 무엇을 묻던
    // 설문인지 알 수 없다 — 회귀 당시 li 0개 · 입력 0개였다.
    const prompts = getSurveyRunSnapshot().questions;
    expect(prompts.length).toBeGreaterThan(0);
    for (const q of prompts) expect(screen.getByText(q.prompt)).toBeTruthy();
  });

  it('입력은 붙이지 않는다 — 제출은 §6 이 409 로 막는다', async () => {
    const { container } = render(<SurveyOverlay payload={{ boothId: MOCK_BOOTH_CLOSED_REWARDED }} />);

    await screen.findByText('마감된 설문입니다');

    expect(container.querySelectorAll('input, textarea')).toHaveLength(0);
  });

  it('"참여하면 N 코인" 을 띄우지 않는다 — 마감인데 참여를 권하지 않는다', async () => {
    render(<SurveyOverlay payload={{ boothId: MOCK_BOOTH_CLOSED_REWARDED }} />);

    await screen.findByText('마감된 설문입니다');

    expect(getSurveyRunSnapshot().rewardCoin).toBeGreaterThan(0); // 조건이 실제로 성립하는 상황이다
    expect(screen.queryByText(REWARD_NOTE)).toBeNull();
  });
});

describe('제출 완료 (S15P21A604-541 F-4)', () => {
  it('"참여하면 N 코인" 이 사라진다 — 이미 받았는데 또 권하지 않는다', async () => {
    render(<SurveyOverlay payload={{ boothId: MOCK_BOOTH_REWARDED }} />);

    await waitFor(() => expect(getSurveyRunSnapshot().status).toBe('ready'));

    // 필수 미답 안내가 보상 안내보다 앞이라, 다 채운 뒤라야 보상 문구가 드러난다
    for (const q of getSurveyRunSnapshot().questions) {
      if (!q.required) continue;
      if (q.type === 'single') setAnswer(q.id, { type: 'single', optionId: q.options?.[0]?.id ?? 'o1' });
      else if (q.type === 'multi') setAnswer(q.id, { type: 'multi', optionIds: [q.options?.[0]?.id ?? 'o1'] });
      else if (q.type === 'rating') setAnswer(q.id, { type: 'rating', value: q.scale?.min ?? 1 });
      else if (q.type === 'short_text') setAnswer(q.id, { type: 'short_text', text: '답' });
      else if (q.type === 'application') setAnswer(q.id, { type: 'application', text: '답' });
      else setAnswer(q.id, { type: 'long_text', text: '답' });
    }
    await waitFor(() => expect(screen.getByText(REWARD_NOTE)).toBeTruthy()); // 제출 전에는 나온다

    await submitSurveyRun();

    await screen.findByText('응답을 제출했습니다');
    expect(screen.queryByText(REWARD_NOTE)).toBeNull();
  });
});
