// Survey Port 선택 지점 — real 어댑터는 아직 없다(BE -130~ 미착수). BE 계약 합의 시
// api.ts(real)+mapper.ts 를 추가하고 여기서 VITE_USE_MOCK 3항으로 갈라지는 것이 교체의 전부다.
import type { SurveyPort } from './api.port';
import { surveyMockPort } from './api.mock';

export const surveyApi: SurveyPort = surveyMockPort;
