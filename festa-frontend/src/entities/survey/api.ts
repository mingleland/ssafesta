// Survey 실 어댑터 (S15P21A604-528). 계약: specs/010-survey/contracts/survey-api.md.
//
// 이 파일에는 변환 로직이 없다 — 경로를 만들고 mapper 를 부르는 것이 전부다. 예외가 둘뿐이다:
//
//   1. getDraft 의 404 SURVEY_NOT_FOUND → null. "아직 만들지 않았다" 는 오류가 아니라 정상 상태다(§3).
//      같은 404 라도 BOOTH_NOT_FOUND 는 그대로 던진다 — 그 구별이 이 처리의 존재 이유다
//   2. getResult 의 2단계. 부스 기준 결과 경로가 계약에 없어 어댑터가 surveyId 를 먼저 해석한다.
//      **BE 에 별칭을 요청해 뒀지만 확정 전이므로 그것을 가정한 코드는 만들지 않는다**(#133) —
//      별칭이 생기면 바뀌는 곳은 이 함수 하나다
import { api } from '../../shared/api/client';
import { isApiError } from '../../shared/api/client';
import type { SurveyAnswerValue, SurveyDraftVM } from '../../shared/contracts/survey';
import type {
  SurveyPort,
  SurveyResultSnapshot,
  SurveyRunSnapshot,
  SurveySubmitResult,
  SurveyTextAnswerPage,
} from './api.port';
import { toDraft, toResultSnapshot, toRunSnapshot, toSaveBody, toTextAnswerPage, toWireAnswers } from './mapper';
import type {
  SurveyResultWire,
  SurveyRunWire,
  SurveySubmitBodyWire,
  SurveySubmitWire,
  SurveyTextPageWire,
  SurveyWire,
} from './types';

const boothSurvey = (boothId: number): string => `/api/v1/booths/${boothId}/survey`;

async function fetchSurvey(boothId: number): Promise<SurveyWire> {
  return api<SurveyWire>(boothSurvey(boothId));
}

export const surveyHttpPort: SurveyPort = {
  async getRun(boothId: number): Promise<SurveyRunSnapshot> {
    return toRunSnapshot(await api<SurveyRunWire>(`${boothSurvey(boothId)}/run`));
  },

  async submitAnswers(surveyId: number, answers: Record<string, SurveyAnswerValue>): Promise<SurveySubmitResult> {
    const body: SurveySubmitBodyWire = { answers: toWireAnswers(answers) };
    const wire = await api<SurveySubmitWire>(`/api/v1/surveys/${surveyId}/responses`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
    return { rewardedCoin: wire.rewardedCoin };
  },

  async getResult(boothId: number): Promise<SurveyResultSnapshot> {
    const survey = await fetchSurvey(boothId);
    return toResultSnapshot(await api<SurveyResultWire>(`/api/v1/surveys/${survey.surveyId}/results`));
  },

  async getTextAnswers(surveyId: number, page: number): Promise<SurveyTextAnswerPage> {
    // size 를 보내지 않는다 — 서버 기본값(20)을 쓰면 §7 이 준 첫 페이지와 크기가 어긋나지 않는다
    return toTextAnswerPage(await api<SurveyTextPageWire>(`/api/v1/surveys/${surveyId}/text-answers?page=${page}`));
  },

  async getDraft(boothId: number): Promise<SurveyDraftVM | null> {
    try {
      return toDraft(await fetchSurvey(boothId));
    } catch (error) {
      if (isApiError(error) && error.code === 'SURVEY_NOT_FOUND') return null;
      throw error;
    }
  },

  async saveDraft(boothId: number, draft: SurveyDraftVM): Promise<void> {
    await api<SurveyWire>(boothSurvey(boothId), {
      method: 'PUT',
      body: JSON.stringify(toSaveBody(draft)),
    });
  },
};
