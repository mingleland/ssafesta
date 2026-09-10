// @vitest-environment jsdom
// 이벤트 설문이 **같은 오버레이**로 그려지는지 (S15P21A604-608).
//
// 이 파일이 지키는 것은 "새 화면을 만들지 않았다" 이다. 문항 목록·제출 버튼·마감 표시가 부스
// 설문과 같은 컴포넌트에서 나와야 하고, 갈리는 것은 진입 열쇠와 그것에 딸린 두 가지 —
// **보상 없는 회원 전용**과 **이미 참여함** — 뿐이다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

vi.mock('../../../../../entities/survey/api.select', async () => {
  const { surveyMockPort } = await import('../../../../../entities/survey/api.mock');
  return { surveyApi: surveyMockPort };
});

const session = vi.fn(() => ({ kind: 'member' }));
vi.mock('../../../../auth/model/session', () => ({ useSession: () => session() }));

import {
  MOCK_BOOTH_NORMAL,
  MOCK_EVENT_SURVEY_DONE,
  MOCK_EVENT_SURVEY_KEY,
  __resetSurveyMockForTests,
} from '../../../../../entities/survey/api.mock';
import { __resetSurveyRunForTests } from '../../../model/run';
import { SurveyOverlay } from '../../SurveyOverlay';

beforeEach(() => {
  __resetSurveyRunForTests();
  __resetSurveyMockForTests();
  session.mockReturnValue({ kind: 'member' });
});

afterEach(cleanup);

describe('회원', () => {
  it('부스 설문과 같은 문항 목록·제출 버튼을 그린다', async () => {
    render(<SurveyOverlay payload={{ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY }} />);

    await waitFor(() => expect(screen.getByRole('button', { name: '제출하기' })).toBeTruthy());
    expect(screen.getByText(/0 \/ \d+ 답변/)).toBeTruthy();
  });

  it('이미 참여했으면 참여 시각을 보이고 제출 버튼을 두지 않는다', async () => {
    render(<SurveyOverlay payload={{ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_DONE }} />);

    await waitFor(() => expect(screen.getByText('이미 참여한 설문입니다')).toBeTruthy());
    expect(screen.queryByRole('button', { name: '제출하기' })).toBeNull();
    expect(screen.getByText('이벤트 설문')).toBeTruthy();
  });
});

describe('게스트', () => {
  it('보상이 0 이어도 막힌다 — 이벤트 설문은 회원 전용이다', async () => {
    session.mockReturnValue({ kind: 'guest' });
    render(<SurveyOverlay payload={{ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY }} />);

    await waitFor(() => expect(screen.getByText('회원만 참여할 수 있는 설문입니다 — 로그인해 주세요')).toBeTruthy());
    expect(screen.getByRole('button', { name: '제출하기' }).hasAttribute('disabled')).toBe(true);
  });

  it('보상 없는 부스 설문은 그대로 열려 있다 — memberOnly 를 부스 경로에 흘리지 않는다', async () => {
    session.mockReturnValue({ kind: 'guest' });
    render(<SurveyOverlay payload={{ kind: 'booth', boothId: MOCK_BOOTH_NORMAL }} />);

    await waitFor(() => expect(screen.getByRole('button', { name: '제출하기' })).toBeTruthy());
    expect(screen.queryByText('회원만 참여할 수 있는 설문입니다 — 로그인해 주세요')).toBeNull();
  });
});
