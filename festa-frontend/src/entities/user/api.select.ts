// users/me API 의 mock/real 선택 지점 — entities/auth/api.select.ts 패턴 복제
import * as realApi from './api';
import * as mockApi from './api.mock';

export const userApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
