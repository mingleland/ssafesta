// mock/real 선택 지점 — 다른 entities와 같은 상용구 (VITE_USE_MOCK)
import * as realApi from './api';
import * as mockApi from './api.mock';

export const walletApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
