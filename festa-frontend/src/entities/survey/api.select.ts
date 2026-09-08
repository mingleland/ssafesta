// Survey Port 선택 지점 — 저장소 공통 관례(entities/catalog/api.select.ts 등)와 같은 한 줄이다.
import type { SurveyPort } from './api.port';
import { surveyHttpPort } from './api';
import { surveyMockPort } from './api.mock';

export const surveyApi: SurveyPort = import.meta.env.VITE_USE_MOCK === 'true' ? surveyMockPort : surveyHttpPort;
