// mock/real 선택 지점 — 다른 entities 와 같은 상용구 (VITE_USE_MOCK)
import { feedbackApi as realApi } from './api';
import { feedbackApi as mockApi } from './api.mock';
import type { FeedbackRepository } from './types';

export const feedbackApi: FeedbackRepository = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
