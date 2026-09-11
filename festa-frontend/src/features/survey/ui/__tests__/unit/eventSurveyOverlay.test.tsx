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
import { __resetSurveyRunForTests, getSurveyRunSnapshot } from '../../../model/run';
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
  it('요청을 만들기 전에 막는다 — 서버가 run 을 회원 전용으로 닫아 두었다 (S15P21A604-621)', async () => {
    session.mockReturnValue({ kind: 'guest' });
    render(<SurveyOverlay payload={{ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY }} />);

    await waitFor(() => expect(screen.getByText('회원만 참여할 수 있습니다')).toBeTruthy());
    // 403 을 받아 오류 화면으로 떨어지는 것이 아니라 안내로 선다
    expect(screen.queryByText('설문을 불러오지 못했습니다')).toBeNull();
    expect(screen.queryByRole('button', { name: '제출하기' })).toBeNull();
    expect(screen.getByText('회원만 참여할 수 있는 설문입니다 — 로그인해 주세요')).toBeTruthy();
  });

  it('로드 자체를 하지 않는다 — 상태 기계가 idle 에 머문다', async () => {
    session.mockReturnValue({ kind: 'guest' });
    render(<SurveyOverlay payload={{ kind: 'event', surveyKey: MOCK_EVENT_SURVEY_KEY }} />);

    await waitFor(() => expect(screen.getByText('회원만 참여할 수 있습니다')).toBeTruthy());
    expect(getSurveyRunSnapshot().status).toBe('idle');
    expect(getSurveyRunSnapshot().source).toBeNull();
  });

  it('보상 없는 부스 설문은 그대로 열려 있다 — memberOnly 를 부스 경로에 흘리지 않는다', async () => {
    session.mockReturnValue({ kind: 'guest' });
    render(<SurveyOverlay payload={{ kind: 'booth', boothId: MOCK_BOOTH_NORMAL }} />);

    await waitFor(() => expect(screen.getByRole('button', { name: '제출하기' })).toBeTruthy());
    expect(screen.queryByText('회원만 참여할 수 있는 설문입니다 — 로그인해 주세요')).toBeNull();
  });
});
